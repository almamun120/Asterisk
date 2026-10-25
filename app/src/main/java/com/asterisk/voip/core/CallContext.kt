package com.asterisk.voip.core

/**
 * What a dial-plan action may do to one call. Implemented by [LegManager]; the
 * [DialPlanExecutor] only talks to this interface, so the actions stay independent
 * of SIP details.
 *
 * Lifecycle: the caller is first either still ringing (unanswered, a failure
 * response from the callee is being held back) or already answered by the server.
 * [answer] switches to the second state; [end] finishes the call in either state.
 */
interface CallContext {
    val fromExt: String
    val toExt: String
    val callId: String

    /** SIP status of the failure that triggered the rules (0 when not applicable). */
    val failureCode: Int

    /** True once the server has answered / originated the call and owns its audio. */
    val answered: Boolean

    /** False when the other side has hung up / cancelled. */
    val alive: Boolean

    /** Answers the caller (200 OK + media). Returns false if that is not possible. */
    suspend fun answer(): Boolean

    /** Plays 8 kHz mono PCM to the caller. False when the caller is gone. */
    suspend fun play(pcm: ShortArray): Boolean

    /** Waits for one DTMF digit (RFC 2833 or SIP INFO). */
    suspend fun waitDigit(timeoutMs: Long): Char?

    /** Records the caller until hang-up, '#', silence on the line, or [maxMs]. */
    suspend fun record(maxMs: Long): ShortArray

    /**
     * Rings [targets] (all at once) and connects the first one that answers to the caller.
     * Plays [moh] in a loop to the caller while ringing.
     * Returns the extension that answered, or null if nobody did within [ringMs].
     */
    suspend fun bridge(targets: List<String>, ringMs: Long, moh: ShortArray?): String?

    /** True if [ext] is registered and not currently on a call. */
    fun available(ext: String): Boolean

    /** Rejects the call with a SIP failure [code] (unanswered) or hangs up (answered). */
    fun reject(code: Int)

    /** Re-routes a still-unanswered call to another extension (the caller keeps ringing). */
    suspend fun forwardTo(ext: String): Boolean

    /** Finishes the call: BYE if answered, otherwise the held failure response goes to the caller. */
    fun end()

    /** Sends a SIP text message to an extension (queued if it is offline). */
    fun sendText(toExt: String, text: String)

    /** Remembers that [ruleId] already ran for this call, so rules cannot loop. */
    fun markFired(ruleId: Long): Boolean // false if it had already run

    var retries: Int
    var hops: Int

    /** Registers a call-back request: when [toExt] is available the server rings [fromExt] and connects them. */
    fun scheduleCallback(maxTries: Int)
}
