package com.android.messaging.dumber

import chat.dumber.sdk.*
import java.util.UUID

internal object ChatGroupActions {
    fun creation(account: String, name: String, saved: GroupCreation?): GroupCreation {
        require(name.isNotBlank() && name.codePointCount(0, name.length) <= 100) {
            "Use a group name of 1–100 characters"
        }
        if (saved != null) {
            check(saved.account == account && saved.phones.isEmpty()) {
                "Discard the previous group request before creating a new group"
            }
            check(saved.name == name) { "Retry the saved name or discard the saved creation first" }
            return saved
        }
        return GroupCreation(account, name, emptyList(), UUID.randomUUID().toString())
    }

    /** The saved invitation is readable before joining; full details may not be. */
    fun joinInvitation(client: ChatApi, invitation: Conversation, ownAccount: String,
                       allowed: (String?) -> Boolean): Conversation {
        check(invitation.kind == ConversationKind.GROUP && invitation.membership == Membership.INVITED) {
            "This group invitation is no longer available"
        }
        check(invitation.members.all { it.contact.id == ownAccount || allowed(it.contact.phone) }) {
            "Resolve and allow all participants before joining"
        }
        client.join(invitation.id)
        // A metadata refresh failure must not report that the successful join failed.
        return runCatching { client.conversationInfo(invitation.id) }.getOrNull()
            ?: membershipSnapshot(client, invitation, ownAccount, Membership.JOINED)
    }

    /** Declining and leaving end access to full details; use the updated local snapshot. */
    fun leave(client: ChatApi, conversation: Conversation, ownAccount: String): Conversation {
        client.leave(conversation.id)
        return membershipSnapshot(client, conversation, ownAccount, Membership.LEFT)
    }

    private fun membershipSnapshot(client: ChatApi, previous: Conversation, ownAccount: String,
                                   membership: Membership): Conversation =
        runCatching { client.conversations().firstOrNull { it.id == previous.id && it.membership == membership } }
            .getOrNull() ?: previous.copy(
                membership = membership,
                capabilities = GroupCapabilities(false, false),
                members = previous.members.map {
                    if (it.contact.id == ownAccount) it.copy(membership = membership) else it
                })

    fun invite(client: ChatApi, id: String, phone: String, ownPhone: String): Conversation {
        require(phone != ownPhone) { "You are already in this group" }
        val info = client.conversationInfo(id)
        check(info.kind == ConversationKind.GROUP && info.membership == Membership.JOINED &&
            info.capabilities.manage) { "Only a joined group admin can add participants" }
        check(info.members.none {
            it.contact.phone == phone && it.membership in listOf(Membership.JOINED, Membership.INVITED)
        }) { "$phone is already a participant or has a pending invitation" }
        client.inviteMember(id, phone)
        return client.conversationInfo(id)
    }
}
