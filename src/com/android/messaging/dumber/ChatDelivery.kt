package com.android.messaging.dumber

import chat.dumber.sdk.*

/** Persist every handoff before calling the network, retaining one transaction ID on retry. */
internal object ChatDelivery {
    fun transmit(
        original: PendingText,
        account: ChatAccount,
        client: ChatApi,
        persist: (PendingText) -> Unit,
        allowed: (String?) -> Boolean
    ): PendingText {
        if (
            original.state == DeliveryState.CONFIRMED ||
                original.state == DeliveryState.CARRIER_QUEUED
        )
            return original
        check(account.enabled) { "Reconnect the original account" }
        check(original.account == account.id) { "The original account changed" }
        check(original.deviceId == null || original.deviceId == account.session.deviceId) {
            "The sending device changed. Review this message before discarding it or sending as SMS."
        }
        var p = original
        check(p.group || allowed(p.recipient)) { "Message blocked by policy" }
        val room =
            if (p.group) {
                val info = client.conversationInfo(requireNotNull(p.conversation))
                check(
                    info.kind == ConversationKind.GROUP &&
                        info.membership == Membership.JOINED &&
                        info.capabilities.send
                ) {
                    "You cannot send to this group"
                }
                check(
                    info.members
                        .filter {
                            it.contact.id != p.account &&
                                it.membership in listOf(Membership.JOINED, Membership.INVITED)
                        }
                        .all { it.contact.phone != null && allowed(it.contact.phone) }
                ) {
                    "Resolve and allow all participants before sending"
                }
                info.id
            } else p.conversation ?: client.openChat(p.recipient, true).id
        p = p.copy(conversation = room, deviceId = account.session.deviceId)
        persist(p)
        val wasUncertain = p.state == DeliveryState.UNCERTAIN
        p = p.copy(state = DeliveryState.UNCERTAIN)
        persist(p)
        val sent =
            try {
                client.sendText(room, p.body, p.id)
            } catch (e: ChatException) {
                if (e.recipientUnavailable && !wasUncertain)
                    persist(p.copy(state = DeliveryState.PENDING, recipientUnavailable = true))
                throw e
            }
        check(sent.encrypted) { "Encryption confirmation missing" }
        return p.copy(state = DeliveryState.CONFIRMED, eventId = sent.eventId, error = "")
            .also(persist)
    }
}

internal object ChatRules {
    fun verificationCode(body: String): String? {
        if (!Regex("(?i)(verification|verify|code|kod)").containsMatchIn(body)) return null
        return Regex("(?<![0-9])[0-9]{6}(?![0-9])")
            .findAll(body)
            .map { it.value }
            .toList()
            .singleOrNull()
    }
    fun matches(account: ChatAccount, sim: Sim) =
        account.subscription == sim.subscription &&
            (account.cardId < 0 || sim.cardId < 0 || account.cardId == sim.cardId) &&
            (sim.number == null ||
                account.reportedNumber == null ||
                sim.number == account.reportedNumber)
    fun useCarrier(status: RecipientStatus?) = status?.availability == Availability.UNAVAILABLE
}
