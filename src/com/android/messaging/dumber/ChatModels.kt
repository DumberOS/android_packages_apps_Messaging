package com.android.messaging.dumber

import chat.dumber.sdk.*

internal enum class AccountStatus {
    CONNECTED,
    LOGGED_OUT,
    SIM_REMOVED,
    REVOKED
}

internal data class ChatAccount(
    val session: Session,
    val phone: String,
    val subscription: Int,
    val cardId: Int,
    val reportedNumber: String?,
    val simLabel: String,
    val storeName: String,
    val storePassword: String,
    val connectedAt: Long,
    val status: AccountStatus = AccountStatus.CONNECTED
) {
    val id
        get() = session.accountId
    val enabled
        get() = status == AccountStatus.CONNECTED
    override fun toString() = "ChatAccount(id=$id, status=$status)"
}

internal data class Verification(
    val nonce: String,
    val phone: String,
    val subscription: Int,
    val cardId: Int,
    val startedAt: Long,
    val expiresAt: Long
)

internal data class SimRemovalChoice(val account: String, val nonce: String, val keep: Boolean)

internal enum class DeliveryState {
    PENDING,
    UNCERTAIN,
    CONFIRMED,
    CARRIER_QUEUED
}

internal data class PendingText(
    val id: String,
    val account: String,
    val subscription: Int,
    val recipient: String,
    val body: String,
    val localConversation: String,
    val conversation: String? = null,
    val group: Boolean = false,
    val state: DeliveryState = DeliveryState.PENDING,
    val eventId: String? = null,
    val error: String = "",
    val recipientUnavailable: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val deviceId: String? = null
)

internal data class GroupState(
    val account: String,
    val conversation: Conversation,
    val date: Long = 0,
    val snippet: String = "",
    val unread: Int = 0,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val hidden: Boolean = false
) {
    val key
        get() = groupKey(account, conversation.id)
}

internal data class GroupCreation(
    val account: String,
    val name: String,
    val phones: List<String>,
    val operationId: String
)

internal data class ReceivedText(
    val account: String,
    val conversation: String,
    val message: Message
)

internal data class Sim(
    val subscription: Int,
    val cardId: Int,
    val name: String,
    val number: String?,
    val displayNumber: String? = number
)

internal fun groupKey(account: String, conversation: String) = digest("$account\u0000$conversation")

internal fun digest(text: String) =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
