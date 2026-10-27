package com.asterisk.voip.core

import com.asterisk.voip.data.model.CallInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/**
 * Server-side audio conference rooms (like Asterisk ConfBridge).
 *
 * How people get in:
 *  - dial the conference code (default `*80`) – `*80` is the main room, `*801`, `*802`, … are other rooms
 *  - from inside the room press `*3`, type an extension and `#`: the server rings that extension and
 *    adds it when it answers (this is the "add another person" step of a three-way call)
 *
 * Audio: every member's G.711 audio is decoded, mixed, and every member receives the mix of all the
 * OTHER members (mix-minus, so nobody hears their own echo). Members may use PCMU or PCMA
 * independently. One mixer thread per room, one 20 ms frame per tick.
 *
 * Keys while in the room:  *6 mute / unmute yourself   *1 how many people are here   *3 add an extension
 */
class ConferenceManager(private val srv: SipServer) {

    companion object {
        private const val FRAME = G711.FRAME
        private const val GATE_LEVEL = 25          // mean |sample| below this = silence (only used with 4+ members)
        private const val GATE_HANG = 15           // frames (300 ms) the gate stays open after speech
        private const val ALONE_HANGUP_MS = 10 * 60_000L
    }

    internal class Cue(val pcm: ShortArray) { var pos = 0 }

    class Member(val ext: String, val leg: LegManager.Leg) {
        internal val fifo = PcmFifo()
        internal val frame = ShortArray(FRAME)
        internal val cueFrame = IntArray(FRAME)
        internal val out = ShortArray(FRAME)
        internal val cues = ConcurrentLinkedQueue<Cue>()
        @Volatile var muted = false
        @Volatile internal var hang = 0
        @Volatile internal var contributing = false
        val joinedAt = System.currentTimeMillis()
    }

    inner class Room(val name: String) {
        val members = CopyOnWriteArrayList<Member>()
        val created = System.currentTimeMillis()
        private val roomCues = ConcurrentLinkedQueue<Cue>()
        private val sum = IntArray(FRAME)
        private val roomCueFrame = IntArray(FRAME)
        @Volatile var running = false
        @Volatile var aloneSince = 0L
        private var thread: Thread? = null

        fun cue(pcm: ShortArray) { roomCues.add(Cue(pcm)) }

        fun start() {
            if (running) return
            running = true
            thread = Thread({ loop() }, "conf-$name").apply { isDaemon = true; start() }
        }

        fun stop() { running = false }

        private fun loop() {
            var next = System.nanoTime()
            while (running) {
                next += 20_000_000L
                try { mixOnce() } catch (e: Exception) { srv.slog("ERR", "Conference mixer: ${e.message}") }
                while (running) {
                    val wait = next - System.nanoTime()
                    if (wait <= 0) { if (wait < -200_000_000L) next = System.nanoTime(); break }
                    LockSupport.parkNanos(wait)
                }
            }
        }

        /** Adds up to one frame of [cues] to [dst] (at half volume) and drops finished cues. */
        private fun pullCues(cues: ConcurrentLinkedQueue<Cue>, dst: IntArray) {
            java.util.Arrays.fill(dst, 0)
            if (cues.isEmpty()) return
            for (c in cues) {
                val n = minOf(FRAME, c.pcm.size - c.pos)
                for (i in 0 until n) dst[i] += c.pcm[c.pos + i].toInt() shr 1
                c.pos += n
                if (c.pos >= c.pcm.size) cues.remove(c)
            }
        }

        private fun mixOnce() {
            val list = members
            val n = list.size
            if (n == 0) return
            val gateOn = n >= 4
            java.util.Arrays.fill(sum, 0)

            for (m in list) {
                m.contributing = false
                val got = m.fifo.read(m.frame)
                if (!got || m.muted || m.leg.closed) continue
                if (gateOn) {
                    var e = 0L
                    for (s in m.frame) e += Math.abs(s.toInt())
                    if (e / FRAME > GATE_LEVEL) m.hang = GATE_HANG else if (m.hang > 0) m.hang--
                    if (m.hang == 0) continue
                }
                m.contributing = true
                for (i in 0 until FRAME) sum[i] += m.frame[i].toInt()
            }

            pullCues(roomCues, roomCueFrame)

            for (m in list) {
                if (m.leg.closed) continue
                pullCues(m.cues, m.cueFrame)
                for (i in 0 until FRAME) {
                    var v = sum[i] + roomCueFrame[i] + m.cueFrame[i]
                    if (m.contributing) v -= m.frame[i].toInt() // mix-minus: not your own voice
                    m.out[i] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
                }
                m.leg.rtp.sendMixFrame(m.out)
            }
        }
    }

    private val rooms = ConcurrentHashMap<String, Room>()
    private val lock = Any()

    // ------------------------------------------------------------ dial codes

    /** `*80` → "main", `*805` → "5". Null when [number] is not a conference number. */
    fun roomFor(number: String): String? {
        val cfg = srv.config
        if (!cfg.confEnabled) return null
        val code = cfg.confCode.trim()
        if (code.isEmpty()) return null
        val n = number.replace("%2A", "*", true).trim()
        if (!n.startsWith(code)) return null
        val rest = n.substring(code.length)
        if (rest.isEmpty()) return "main"
        return if (rest.length <= 6 && rest.all { it.isDigit() }) rest else null
    }

    fun label(room: String) = if (room == "main") srv.config.confCode.trim() else srv.config.confCode.trim() + room

    fun isFull(room: String): Boolean {
        val r = rooms[room] ?: return false
        return r.members.size >= maxMembers()
    }

    private fun maxMembers() = srv.config.confMaxMembers.coerceIn(2, 50)

    private fun join(name: String, m: Member): Room? = synchronized(lock) {
        val room = rooms.getOrPut(name) { Room(name) }
        if (room.members.size >= maxMembers()) return null
        m.leg.rtp.mixIn = m.fifo
        room.members.add(m)
        room.aloneSince = if (room.members.size == 1) System.currentTimeMillis() else 0L
        room.start()
        room
    }

    /** Returns how many members are left. */
    private fun leave(room: Room, m: Member): Int = synchronized(lock) {
        m.leg.rtp.mixIn = null
        room.members.remove(m)
        val left = room.members.size
        if (left == 0) { room.stop(); rooms.remove(room.name, room) }
        else if (left == 1) room.aloneSince = System.currentTimeMillis()
        left
    }

    // ------------------------------------------------------------ one member

    /**
     * Runs one participant: optional PIN, welcome, joins the mix, handles the keys, and returns when the
     * participant hangs up. [invitedBy] is set for people the room rang itself (no PIN asked).
     */
    suspend fun runMember(roomName: String, leg: LegManager.Leg, ext: String, invitedBy: String?) {
        val cfg = srv.config
        val lm = srv.legManager

        if (invitedBy == null && cfg.confPin.isNotBlank()) {
            if (!askPin(leg, cfg.confPin.trim())) {
                srv.slog("CONF", "🔒 $ext failed the PIN for conference ${label(roomName)}")
                speak(leg, "Wrong PIN. Goodbye.")
                lm.closeLeg(leg, true)
                return
            }
        }

        val member = Member(ext, leg)
        val alone = (rooms[roomName]?.members?.size ?: 0) == 0
        if (invitedBy != null) {
            speak(leg, "You have been invited to a conference by extension ${spell(invitedBy)}.")
        } else if (alone) {
            speak(leg, "You are the only person in the conference. Please wait for others.")
        }
        if (leg.closed) return

        val room = join(roomName, member)
        if (room == null) {
            speak(leg, "The conference is full. Goodbye.")
            lm.closeLeg(leg, true)
            return
        }
        lm.setBusy(ext, true)
        srv.slog("CONF", "➕ $ext joined conference ${label(roomName)} (${room.members.size} in room)")
        room.cue(Tones.joinTone())

        try {
            commandLoop(room, member)
        } finally {
            val left = leave(room, member)
            lm.setBusy(ext, false)
            srv.slog("CONF", "➖ $ext left conference ${label(roomName)} ($left in room)")
            if (left > 0) room.cue(Tones.leaveTone())
            if (!leg.closed) lm.closeLeg(leg, true)
        }
    }

    private suspend fun commandLoop(room: Room, m: Member) {
        val rtp = m.leg.rtp
        while (!m.leg.closed && !rtp.closed) {
            // nobody else came for a long time: hang up so the line is not held forever
            if (room.members.size == 1 && room.aloneSince > 0 && System.currentTimeMillis() - room.aloneSince > ALONE_HANGUP_MS) {
                cue(m, "Nobody else joined. Goodbye.")
                delay(3500)
                return
            }
            val d = withTimeoutOrNull(1000) { rtp.dtmf.receiveCatching().getOrNull() } ?: continue
            if (d != '*') continue
            val c = withTimeoutOrNull(3000) { rtp.dtmf.receiveCatching().getOrNull() } ?: continue
            when (c) {
                '6' -> {
                    m.muted = !m.muted
                    m.cues.add(Cue(if (m.muted) Tones.mutedTone() else Tones.unmutedTone()))
                    srv.slog("CONF", "${if (m.muted) "🔇" else "🔊"} ${m.ext} ${if (m.muted) "muted" else "unmuted"}")
                }
                '1' -> {
                    val n = room.members.size
                    cue(m, if (n == 1) "You are the only person in the conference." else "There are $n people in the conference.")
                }
                '3' -> addByDialing(room, m)
            }
        }
    }

    /** `*3` + extension + `#` : the server calls that extension and puts it into this room. */
    private suspend fun addByDialing(room: Room, m: Member) {
        cue(m, "Enter the extension to add, then press hash.")
        val rtp = m.leg.rtp
        val digits = StringBuilder()
        while (digits.length < 12 && !m.leg.closed) {
            val d = withTimeoutOrNull(8000) { rtp.dtmf.receiveCatching().getOrNull() } ?: break
            if (d == '#') break
            if (d.isDigit()) digits.append(d)
        }
        val target = digits.toString()
        if (target.isEmpty()) return
        if (target == m.ext || room.members.any { it.ext == target }) { cue(m, "That extension is already in the conference."); return }
        if (room.members.size >= maxMembers()) { cue(m, "The conference is full."); return }
        cue(m, "Calling extension ${spell(target)}.")
        srv.slog("CONF", "📞 ${m.ext} is adding $target to conference ${label(room.name)}")
        srv.scope.launch { invite(room.name, m, target) }
    }

    private suspend fun invite(roomName: String, by: Member, ext: String) {
        val lm = srv.legManager
        val leg = lm.dialAudio(ext, "conference", 30_000L)
        if (leg == null) {
            srv.slog("CONF", "❌ $ext did not answer / is not available")
            if (!by.leg.closed) cue(by, "Extension ${spell(ext)} is not available.")
            return
        }
        runMember(roomName, leg, ext, by.ext)
    }

    // ------------------------------------------------------------ helpers

    private fun spell(s: String) = s.toList().joinToString(" ")

    private suspend fun askPin(leg: LegManager.Leg, pin: String): Boolean {
        val rtp = leg.rtp
        repeat(3) {
            speak(leg, "Please enter the conference PIN, followed by hash.")
            val got = StringBuilder()
            while (got.length < 16 && !leg.closed) {
                val d = withTimeoutOrNull(8000) { rtp.dtmf.receiveCatching().getOrNull() } ?: break
                if (d == '#') break
                got.append(d)
            }
            if (leg.closed) return false
            if (got.toString() == pin) return true
        }
        return false
    }

    /** Plays [text] straight to one leg that is NOT in the mix yet. */
    private suspend fun speak(leg: LegManager.Leg, text: String) {
        val pcm = srv.prompts?.renderTts(text, "en-US") ?: Tones.beep()
        leg.rtp.playPcm(pcm) { leg.closed }
    }

    /** Whispers [text] to one member who is already in the mix. */
    private suspend fun cue(m: Member, text: String) {
        val pcm = srv.prompts?.renderTts(text, "en-US") ?: Tones.beep()
        m.cues.add(Cue(pcm))
    }

    // ------------------------------------------------------------ status for the dashboard

    fun activeRooms(): List<CallInfo> = rooms.values.filter { it.members.isNotEmpty() }.map { r ->
        CallInfo(
            id = "conf-${r.name}",
            fromExt = r.members.joinToString(", ") { it.ext + if (it.muted) " (muted)" else "" },
            toExt = "Conference ${label(r.name)}",
            state = "CONFERENCE ×${r.members.size}",
            startedAt = r.created,
            relayed = true,
            video = false,
            packets = 0L
        )
    }

    fun memberCount(): Int = rooms.values.sumOf { it.members.size }

    fun stop() {
        rooms.values.forEach { it.stop() }
        rooms.clear()
    }
}
