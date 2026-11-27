package com.android.messaging.dumber

import chat.dumber.sdk.SmsRequest

/** Replayed requests never create another message; Bugle owns carrier retries. */
internal object ChatSmsPolicy {
    const val MAX_AGE_MS = 10 * 60_000L
    fun fresh(request: SmsRequest, now: Long): Boolean =
        request.createdAtMs > 0 && request.receivedAtMs > 0 &&
        request.createdAtMs <= now + 30_000 && request.receivedAtMs <= now + 30_000 &&
        now - request.createdAtMs < MAX_AGE_MS && now - request.receivedAtMs < MAX_AGE_MS

    fun valid(request: SmsRequest) = request.kind in listOf("send", "send_v2") &&
        (request.kind != "send_v2" || (Regex("[0-9a-f]{64}").matches(request.simId) && request.transport in listOf("sms", "encrypted"))) &&
        Regex("[0-9a-fA-F-]{36}").matches(request.requestId) &&
        Regex("\\+[1-9][0-9]{7,14}").matches(request.address) &&
        request.body.isNotBlank() && request.body.length <= (if (request.kind == "send_v2" && request.transport == "encrypted") 10000 else 1600)

    fun simId(sim: Sim, account: ChatAccount?) = digest("${sim.subscription}/${sim.cardId}/${sim.number.orEmpty()}/${account?.let(ChatHistoryTransfer::sessionKey).orEmpty()}")
    fun transport(hasAccount: Boolean, availability: chat.dumber.sdk.Availability?) = when {
        !hasAccount -> "sms"
        availability == chat.dumber.sdk.Availability.AVAILABLE -> "encrypted"
        availability == chat.dumber.sdk.Availability.UNAVAILABLE -> "sms"
        else -> "unknown"
    }
    fun matchesRoute(request: SmsRequest, simId: String, transport: String) =
        request.kind == "send_v2" && request.simId == simId && request.transport == transport && transport != "unknown"

    fun initial(request: SmsRequest, now: Long): String = when {
        !fresh(request, now) -> "expired"
        !valid(request) -> "failed"
        else -> "unknown"
    }
}

internal data class SmsAttempt @JvmOverloads constructor(
    val account: String, val sessionKey: String, val request: SmsRequest,
    val state: String, val uri: String = "", val parts: List<Int> = emptyList(),
    val matrixRoom: String = "", val matrixEvent: String = "", val matrixAccount: String = "",
    val queueVersion: Int = 0, val subscription: Int = -1, val pinnedSim: String = "", val localId: String = ""
)
internal data class SmsExport(val account: String, val sessionKey: String, val id: String, val address: String, val fingerprint: String)

internal data class SmsSnapshot(val account: String, val sessionKey: String, val generation: Long,
    val requestIds: List<String>, val count: Long = 0, val finished: Boolean = false) {
    fun progress(offset: Int, batchSize: Int, remaining: Int) = chat.dumber.sdk.SmsProgress(
        generation, requestIds, count + offset, count + remaining, offset + batchSize == remaining)
}
