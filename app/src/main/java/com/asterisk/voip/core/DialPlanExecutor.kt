package com.asterisk.voip.core

import com.asterisk.voip.data.model.*
import com.asterisk.voip.data.repository.AsteriskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the dial-plan rules for a call that could not be completed normally.
 *
 * Rules matching the failure condition run in priority order as a chain, e.g.
 *   1. Play Announcement  (caller hears a prompt)
 *   2. Send to Voicemail  (caller can leave a message)
 * Each rule runs at most once per call (except Retry), so rules can never loop.
 *
 * Action data formats (all optional unless noted):
 *   PLAY_ANNOUNCEMENT   promptId[,promptId...]
 *   VOICEMAIL           [greetingPromptId][;max=SECONDS]       (mailbox = the called extension)
 *   FORWARD_TO_EXTENSION extension                               (required)
 *   FORWARD_TO_QUEUE    101,102,103[;strategy=ringall|linear|random|leastrecent|fewestcalls|rrmemory][;timeout=SEC][;moh=promptId]
 *   FORWARD_TO_IVR      [greetingPromptId]|1=ext:101|2=queue:101,102|3=vm:105|4=play:7|0=hangup
 *   CALLBACK            [promptIdToPlayFirst]
 *   RETRY               (uses the rule's Max Retries; waits min(Timeout,5) s between tries)
 *   CUSTOM              reject:486 ; tone:busy ; message:text ; play:ID ; hangup   (separate with ';')
 */
class DialPlanExecutor(
    private val repository: AsteriskRepository,
    private val voicePromptManager: VoicePromptManager,
    private val storageDir: File,
    private val logger: (LogLevel, String, String) -> Unit
) {
    private enum class Step { CONTINUE, DONE, SKIPPED }

    @Volatile private var rules: List<DialPlanRule> = emptyList()
    @Volatile var cfg: ServerConfig = ServerConfig()

    private val lastAnswered = ConcurrentHashMap<String, Long>()
    private val answeredCount = ConcurrentHashMap<String, Int>()
    private val rrIndex = ConcurrentHashMap<String, Int>()

    fun configure(config: ServerConfig) { cfg = config }

    /** Reloads the enabled rules (called regularly by the server so edits apply without restart). */
    suspend fun refresh() {
        try { rules = repository.getEnabledRulesNow() } catch (_: Exception) {}
    }

    private fun matching(cond: DialPlanCondition, toExt: String): List<DialPlanRule> =
        rules.filter { it.enabled && it.condition == cond && matchesPattern(toExt, it.pattern) }.sortedBy { it.priority }

    /** True if some rule (not yet used by this call) exists for one of [conds]. */
    fun hasRules(conds: List<DialPlanCondition>, toExt: String, fired: Set<Long>): Boolean =
        conds.any { c -> matching(c, toExt).any { it.id !in fired || it.action == DialPlanAction.RETRY } }

    /** Ring time (ms) before "no answer" fires, from a matching No Answer / Timeout rule; null = server default. */
    fun ringTimeoutMs(toExt: String): Long? {
        val r = matching(DialPlanCondition.NO_ANSWER, toExt).firstOrNull()
            ?: matching(DialPlanCondition.TIMEOUT, toExt).firstOrNull()
            ?: return null
        return r.timeout.coerceIn(5, 180) * 1000L
    }

    // ------------------------------------------------------------ main entry

    /** Returns true if at least one rule ran. */
    suspend fun execute(conds: List<DialPlanCondition>, ctx: CallContext): Boolean {
        val group = conds.map { matching(it, ctx.toExt) }.firstOrNull { it.isNotEmpty() } ?: return false
        var ran = false
        for (rule in group) {
            if (!ctx.alive) break
            if (rule.action != DialPlanAction.RETRY && !ctx.markFired(rule.id)) continue
            ran = true
            log("▶ Rule \"${rule.name}\" (${rule.condition} → ${rule.action}) for ${ctx.fromExt} → ${ctx.toExt}")
            val step = try {
                run(rule, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("✗ Rule \"${rule.name}\" failed: ${e.message}")
                Step.SKIPPED
            }
            if (step == Step.DONE) break
        }
        return ran
    }

    private suspend fun run(rule: DialPlanRule, ctx: CallContext): Step = when (rule.action) {
        DialPlanAction.PLAY_ANNOUNCEMENT -> playPrompts(rule.actionData, ctx)
        DialPlanAction.VOICEMAIL -> {
            val parts = rule.actionData.split(';').map { it.trim() }
            val greeting = parts.firstOrNull { it.isNotEmpty() && !it.contains('=') }?.toLongOrNull()
            val max = parts.firstOrNull { it.startsWith("max=") }?.substringAfter('=')?.toIntOrNull()
            voicemail(ctx.toExt, greeting, max, ctx)
        }
        DialPlanAction.FORWARD_TO_EXTENSION -> forward(rule, ctx)
        DialPlanAction.FORWARD_TO_QUEUE -> if (queue(rule.actionData, ctx)) Step.DONE else Step.CONTINUE
        DialPlanAction.FORWARD_TO_IVR -> ivr(rule.actionData, ctx)
        DialPlanAction.HANGUP -> { ctx.end(); Step.DONE }
        DialPlanAction.CALLBACK -> {
            rule.actionData.trim().toLongOrNull()?.let { playPrompts(it.toString(), ctx) }
            ctx.scheduleCallback(rule.maxRetries.coerceAtLeast(1))
            Step.CONTINUE
        }
        DialPlanAction.RETRY -> retry(rule, ctx)
        DialPlanAction.CUSTOM -> custom(rule.actionData, ctx)
    }

    // ------------------------------------------------------------ announcement

    private suspend fun playPrompts(data: String, ctx: CallContext): Step {
        val ids = data.split(',', ';').mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) { log("✗ Play Announcement needs a voice prompt ID"); return Step.SKIPPED }
        var played = false
        for (id in ids) {
            val prompt = repository.getVoicePromptById(id)
            if (prompt == null || !prompt.enabled) { log("✗ Voice prompt $id missing or disabled"); continue }
            val pcm = voicePromptManager.render(prompt)
            if (pcm == null || pcm.isEmpty()) { log("✗ Voice prompt \"${prompt.name}\" could not be rendered"); continue }
            if (!ctx.answer()) return if (played) Step.CONTINUE else Step.SKIPPED
            log("🔊 Playing \"${prompt.name}\" to caller ${ctx.fromExt}")
            if (!ctx.play(pcm)) return Step.DONE // caller hung up
            played = true
        }
        if (played) ctx.play(G711.silence(300)) // let the last syllable finish before hang-up
        return if (played) Step.CONTINUE else Step.SKIPPED
    }

    private suspend fun say(ctx: CallContext, text: String): Boolean {
        val pcm = voicePromptManager.renderTts(text, "en-US") ?: return ctx.alive
        if (!ctx.answer()) return false
        return ctx.play(pcm)
    }

    // ------------------------------------------------------------ voicemail

    private suspend fun voicemail(mailbox: String, greetingId: Long?, maxSecOverride: Int?, ctx: CallContext): Step {
        val c = cfg
        if (!c.voicemailEnabled) { log("✗ Voicemail is disabled in Settings"); return Step.SKIPPED }
        if (!ctx.answer()) return Step.SKIPPED

        if (c.voicemailMaxMessage > 0 && repository.getVoicemailCount(mailbox) >= c.voicemailMaxMessage) {
            say(ctx, "The mailbox is full. Please try again later.")
            ctx.end(); return Step.DONE
        }

        var greeted = false
        if (greetingId != null) {
            val p = repository.getVoicePromptById(greetingId)
            val pcm = if (p != null && p.enabled) voicePromptManager.render(p) else null
            if (pcm != null) { if (!ctx.play(pcm)) return Step.DONE; greeted = true }
        }
        if (!greeted && !say(ctx, "Please leave a message after the tone. Press hash when you are finished.")) return Step.DONE
        if (!ctx.play(Tones.beep())) return Step.DONE

        val maxSec = (maxSecOverride ?: c.voicemailMaxSeconds).coerceIn(5, 900)
        val pcm = ctx.record(maxSec * 1000L)
        val secs = pcm.size / G711.RATE
        if (secs < c.voicemailMinSeconds.coerceAtLeast(1)) {
            log("📭 Voicemail for $mailbox discarded (only ${secs}s)")
            ctx.end(); return Step.DONE
        }
        val ts = System.currentTimeMillis()
        val file = File(storageDir, "voicemail/$mailbox/${ts}_${ctx.fromExt}.wav")
        G711.writeWav(file, pcm)
        repository.insertVoicemail(Voicemail(mailbox = mailbox, fromExt = ctx.fromExt, filePath = file.absolutePath,
            durationSec = secs, timestamp = ts))
        log("📬 Voicemail saved for $mailbox from ${ctx.fromExt} (${secs}s)")
        ctx.sendText(mailbox, "📧 New voicemail from ${ctx.fromExt} (${secs}s). Dial *97 to listen.")
        if (ctx.alive) { say(ctx, "Thank you. Goodbye."); ctx.end() }
        return Step.DONE
    }

    /** Dial *97: the extension listens to its own voicemails. */
    suspend fun voicemailMenu(mailbox: String, ctx: CallContext) {
        if (!ctx.answer()) return
        val list = repository.getVoicemails(mailbox)
        if (list.isEmpty()) { say(ctx, "You have no messages. Goodbye."); ctx.end(); return }
        val unheard = list.count { !it.heard }
        if (!say(ctx, "You have $unheard new messages and ${list.size} in total.")) return
        val fmt = SimpleDateFormat("d MMMM, h:mm a", Locale.US)
        for (vm in list) {
            if (!ctx.alive) return
            val spelled = vm.fromExt.toList().joinToString(" ")
            if (!say(ctx, "Message from extension $spelled, received ${fmt.format(java.util.Date(vm.timestamp))}.")) return
            val pcm = AudioDecoder.decodeTo8k(File(vm.filePath))
            if (pcm != null && !ctx.play(pcm)) return
            repository.markVoicemailHeard(vm.id)
            if (!say(ctx, "Press 7 to delete this message, hash to exit, or any other key for the next one.")) return
            when (ctx.waitDigit(7000)) {
                '7' -> {
                    try { File(vm.filePath).delete() } catch (_: Exception) {}
                    repository.deleteVoicemail(vm.id)
                    if (!say(ctx, "Message deleted.")) return
                }
                '#' -> { ctx.end(); return }
                else -> {}
            }
        }
        say(ctx, "No more messages. Goodbye.")
        ctx.end()
    }

    // ------------------------------------------------------------ forward / retry / custom

    private suspend fun forward(rule: DialPlanRule, ctx: CallContext): Step {
        val target = rule.actionData.trim()
        if (target.isEmpty()) { log("✗ Forward needs an extension number"); return Step.SKIPPED }
        if (target == ctx.toExt || ctx.hops >= 5) { log("✗ Forward to $target refused (loop)"); return Step.SKIPPED }
        ctx.hops++
        if (ctx.answered) {
            val who = ctx.bridge(listOf(target), rule.timeout.coerceIn(5, 120) * 1000L, null)
            return if (who != null) Step.DONE else Step.SKIPPED
        }
        return if (ctx.forwardTo(target)) {
            log("➡ Call forwarded: ${ctx.fromExt} → $target")
            Step.DONE
        } else Step.SKIPPED
    }

    private suspend fun retry(rule: DialPlanRule, ctx: CallContext): Step {
        if (ctx.answered || ctx.retries >= rule.maxRetries) return Step.SKIPPED
        ctx.retries++
        log("🔄 Retry ${ctx.retries}/${rule.maxRetries} → ${ctx.toExt}")
        delay(rule.timeout.coerceIn(1, 5) * 1000L)
        if (!ctx.alive) return Step.DONE
        return if (ctx.forwardTo(ctx.toExt)) Step.DONE else Step.SKIPPED
    }

    private suspend fun custom(data: String, ctx: CallContext): Step {
        for (raw in data.split(';')) {
            val cmd = raw.trim()
            if (cmd.isEmpty()) continue
            val name = cmd.substringBefore(':').trim().lowercase()
            val arg = cmd.substringAfter(':', "").trim()
            when (name) {
                "reject" -> { ctx.reject(arg.toIntOrNull() ?: 486); return Step.DONE }
                "hangup" -> { ctx.end(); return Step.DONE }
                "tone" -> {
                    if (!ctx.answer()) return Step.SKIPPED
                    if (!ctx.play(Tones.generate(arg))) return Step.DONE
                }
                "play" -> if (playPrompts(arg, ctx) == Step.DONE) return Step.DONE
                "message" -> ctx.sendText(ctx.toExt, arg.replace("{from}", ctx.fromExt))
                else -> log("✗ Unknown custom command: $cmd")
            }
        }
        return Step.CONTINUE
    }

    // ------------------------------------------------------------ queue

    private fun params(spec: String): Pair<String, Map<String, String>> {
        val parts = spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val head = parts.firstOrNull { !it.contains('=') } ?: ""
        val map = HashMap<String, String>()
        for (p in parts) if (p.contains('=')) map[p.substringBefore('=').trim().lowercase()] = p.substringAfter('=').trim()
        return Pair(head, map)
    }

    private fun order(avail: List<String>, strategy: String, key: String): List<String> = when (strategy) {
        "random" -> avail.shuffled()
        "leastrecent" -> avail.sortedBy { lastAnswered[it] ?: 0L }
        "fewestcalls" -> avail.sortedBy { answeredCount[it] ?: 0 }
        "rrmemory" -> {
            val start = (rrIndex[key] ?: 0) % avail.size
            avail.drop(start) + avail.take(start)
        }
        else -> avail
    }

    /** Returns true if a member answered (and the bridged call has ended). */
    private suspend fun queue(spec: String, ctx: CallContext): Boolean {
        val (head, p) = params(spec)
        val members = head.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (members.isEmpty()) { log("✗ Queue has no members"); return false }
        if (!ctx.answer()) return false

        val c = cfg
        val strategy = (p["strategy"] ?: c.queueStrategy).lowercase()
        val totalMs = (p["timeout"]?.toIntOrNull() ?: c.queueTimeout).coerceIn(10, 3600) * 1000L
        var moh: ShortArray? = null
        val mohId = p["moh"]?.toLongOrNull()
        if (mohId != null) {
            val mp = repository.getVoicePromptById(mohId)
            if (mp != null && mp.enabled) moh = voicePromptManager.render(mp)
        }
        val key = members.joinToString(",")
        val deadline = System.currentTimeMillis() + totalMs
        var attempt = 0
        say(ctx, "All our agents are busy. Please hold, your call is important to us.")

        while (ctx.alive && System.currentTimeMillis() < deadline) {
            val avail = members.filter { ctx.available(it) }
            if (avail.isEmpty()) {
                if (!ctx.play(moh ?: G711.silence(3000))) return false
                continue
            }
            val ordered = order(avail, strategy, key)
            val targets: List<String>
            val ringMs: Long
            if (strategy == "ringall") {
                targets = ordered; ringMs = 20_000L
            } else {
                targets = listOf(ordered[attempt % ordered.size]); ringMs = 15_000L
            }
            attempt++
            val left = deadline - System.currentTimeMillis()
            if (left < 3000) break
            val who = ctx.bridge(targets, minOf(ringMs, left), moh)
            if (who != null) {
                lastAnswered[who] = System.currentTimeMillis()
                answeredCount[who] = (answeredCount[who] ?: 0) + 1
                rrIndex[key] = (rrIndex[key] ?: 0) + 1
                return true
            }
        }
        if (ctx.alive) say(ctx, "We are sorry, nobody is available to take your call.")
        return false
    }

    // ------------------------------------------------------------ IVR

    private suspend fun ivr(spec: String, ctx: CallContext): Step {
        val parts = spec.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val greetingId = parts.firstOrNull { !it.contains('=') }?.toLongOrNull()
        val options = HashMap<Char, String>()
        for (p in parts) if (p.contains('=')) {
            val d = p.substringBefore('=').trim()
            if (d.length == 1) options[d[0]] = p.substringAfter('=').trim()
        }
        if (options.isEmpty()) { log("✗ IVR has no options (format: promptId|1=ext:101|2=queue:101,102)"); return Step.SKIPPED }
        if (!ctx.answer()) return Step.SKIPPED

        var greeting: ShortArray? = null
        if (greetingId != null) {
            val gp = repository.getVoicePromptById(greetingId)
            if (gp != null && gp.enabled) greeting = voicePromptManager.render(gp)
        }
        if (greeting == null) greeting = voicePromptManager.renderTts("Welcome. Please choose an option.", "en-US")

        val maxTries = cfg.ivrMaxRetries.coerceIn(1, 10)
        var tries = 0
        while (ctx.alive && tries < maxTries) {
            if (greeting != null && !ctx.play(greeting)) return Step.DONE
            val d = ctx.waitDigit(cfg.ivrTimeout.coerceIn(3, 60) * 1000L)
            if (d == null) {
                tries++
                if (!say(ctx, "We did not receive any input.")) return Step.DONE
                continue
            }
            val target = options[d]
            if (target == null) {
                tries++
                if (!say(ctx, "Invalid option.")) return Step.DONE
                continue
            }
            log("🎛 IVR: ${ctx.fromExt} pressed $d → $target")
            if (ivrTarget(target, ctx)) return Step.DONE
            tries++
        }
        if (ctx.alive) { say(ctx, "Goodbye."); ctx.end() }
        return Step.DONE
    }

    /** Returns true when the call is finished (hung up / bridged and ended). */
    private suspend fun ivrTarget(target: String, ctx: CallContext): Boolean {
        val kind = target.substringBefore(':').trim().lowercase()
        val arg = target.substringAfter(':', "").trim()
        when (kind) {
            "ext" -> {
                val who = ctx.bridge(listOf(arg), 30_000L, null)
                if (who != null) return true
                if (!ctx.alive) return true
                say(ctx, "The extension is not available.")
                return false
            }
            "queue" -> {
                if (queue(arg, ctx)) return true
                return !ctx.alive
            }
            "vm" -> {
                val v = voicemail(arg.ifEmpty { ctx.toExt }, null, null, ctx)
                return v == Step.DONE
            }
            "play" -> {
                playPrompts(arg, ctx)
                return false
            }
            "hangup" -> { ctx.end(); return true }
        }
        log("✗ Unknown IVR target: $target")
        return false
    }

    // ------------------------------------------------------------ helpers

    private fun matchesPattern(extension: String, pattern: String): Boolean {
        val p = pattern.trim()
        return when {
            p == "*" || p.isEmpty() -> true
            p == extension -> true
            p.contains("X") || p.contains("[") -> try {
                p.replace("X", "\\d").toRegex().matches(extension)
            } catch (_: Exception) { false }
            else -> false
        }
    }

    private fun log(message: String) = logger(LogLevel.INFO, "DIALPLAN", message)
}

data class DialPlanResult(
    val success: Boolean,
    val message: String,
    val nextAction: String? = null
)
