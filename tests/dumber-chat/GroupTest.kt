package com.android.messaging.dumber

import chat.dumber.sdk.*
import java.lang.reflect.Proxy

fun main() {
    var count = 0
    fun test(name: String, run: () -> Unit) {
        run()
        println("PASS $name")
        count++
    }
    fun fails(message: String, run: () -> Unit) {
        check(runCatching(run).exceptionOrNull()?.message?.contains(message) == true)
    }
    test("creation starts alone and retries the exact saved operation") {
        val first = ChatGroupActions.creation("me", "Family", null)
        check(first.phones.isEmpty())
        check(ChatGroupActions.creation("me", "Family", first) == first)
        fails("saved name") { ChatGroupActions.creation("me", "Other", first) }
        fails("previous group") { ChatGroupActions.creation("me", "Family", first.copy(phones = listOf("+46123456789"))) }
        fails("previous group") { ChatGroupActions.creation("other", "Family", first) }
    }
    test("empty and overlong group names are rejected before saving") {
        fails("group name") { ChatGroupActions.creation("me", " ", null) }
        fails("group name") { ChatGroupActions.creation("me", "x".repeat(101), null) }
    }
    val own = "+46123456789"
    val peer = "+46987654321"
    val room = Conversation("group", ConversationKind.GROUP, "Family",
        members = listOf(GroupMember(Contact("me", own), Membership.JOINED, true)),
        capabilities = GroupCapabilities(true, true), membership = Membership.JOINED)
    var current = room
    var calls = mutableListOf<String>()
    var unavailable = false
    val client = Proxy.newProxyInstance(ChatApi::class.java.classLoader, arrayOf(ChatApi::class.java)) { _, method, args ->
        calls.add(method.name)
        when (method.name) {
            "conversationInfo" -> current
            "inviteMember" -> {
                check(args[0] == "group" && args[1] == peer)
                if (unavailable) throw ChatException("$peer is not available on dumber.chat", ErrorKind.RECIPIENT_UNAVAILABLE)
                current = current.copy(members = current.members + GroupMember(Contact("peer", peer), Membership.INVITED))
                null
            }
            else -> error("Unexpected call: ${method.name}")
        }
    } as ChatApi
    test("inviting from a solo group refreshes permissions and returns the invitation") {
        val result = ChatGroupActions.invite(client, room.id, peer, own)
        check(result.members.last().membership == Membership.INVITED)
        check(calls == listOf("conversationInfo", "inviteMember", "conversationInfo"))
    }
    test("own number and duplicate invitations do not send another invite") {
        calls.clear()
        fails("already in") { ChatGroupActions.invite(client, room.id, own, own) }
        check(calls.isEmpty())
        fails("already a participant") { ChatGroupActions.invite(client, room.id, peer, own) }
        check(calls == listOf("conversationInfo"))
    }
    test("permission changes prevent sending an invitation") {
        for (info in listOf(room.copy(capabilities = GroupCapabilities(false, true)),
            room.copy(membership = Membership.LEFT), room.copy(kind = ConversationKind.DIRECT))) {
            current = info
            calls.clear()
            fails("admin") { ChatGroupActions.invite(client, room.id, peer, own) }
            check(calls == listOf("conversationInfo"))
        }
    }
    test("unavailable participant leaves the group intact and preserves a useful error") {
        current = room
        unavailable = true
        fails(peer) { ChatGroupActions.invite(client, room.id, peer, own) }
        check(current == room)
    }
    val invitation = room.copy(membership = Membership.INVITED,
        capabilities = GroupCapabilities(false, false), members = listOf(
            GroupMember(Contact("me", own), Membership.INVITED),
            GroupMember(Contact("peer", peer), Membership.JOINED, true)))
    var joined = false
    var refreshFails = false
    var snapshotFails = false
    var mutationFails = false
    val membershipClient = Proxy.newProxyInstance(ChatApi::class.java.classLoader, arrayOf(ChatApi::class.java)) { _, method, _ ->
        calls.add(method.name)
        when (method.name) {
            "conversationInfo" -> {
                // Reproduce the backend's refusal before joining and after leaving.
                if (!joined || refreshFails) throw ChatException("Operation not permitted", ErrorKind.PERMISSION_DENIED)
                invitation.copy(membership = Membership.JOINED, capabilities = GroupCapabilities(false, true))
            }
            "join" -> {
                if (mutationFails) throw ChatException("Join denied", ErrorKind.PERMISSION_DENIED)
                joined = true; null
            }
            "leave" -> {
                if (mutationFails) throw ChatException("Leave denied", ErrorKind.PERMISSION_DENIED)
                joined = false; null
            }
            "conversations" -> {
                if (snapshotFails) error("Snapshot unavailable")
                listOf(invitation.copy(membership = if (joined) Membership.JOINED else Membership.LEFT))
            }
            else -> error("Unexpected call: ${method.name}")
        }
    } as ChatApi
    test("join acts on the invitation before requesting full details") {
        calls.clear()
        val result = ChatGroupActions.joinInvitation(membershipClient, invitation, "me") { true }
        check(result.membership == Membership.JOINED && result.capabilities.send)
        check(calls == listOf("join", "conversationInfo"))
    }
    test("decline and leave do not request forbidden group details") {
        for (previous in listOf(invitation, room)) {
            calls.clear()
            val result = ChatGroupActions.leave(membershipClient, previous, "me")
            check(result.membership == Membership.LEFT && !result.capabilities.send && !result.capabilities.manage)
            check(calls == listOf("leave", "conversations"))
        }
    }
    test("successful membership changes survive failed metadata refreshes") {
        calls.clear()
        refreshFails = true
        val joinedResult = ChatGroupActions.joinInvitation(membershipClient, invitation, "me") { true }
        check(joinedResult.membership == Membership.JOINED)
        check(calls == listOf("join", "conversationInfo", "conversations"))
        snapshotFails = true
        val leftResult = ChatGroupActions.leave(membershipClient, room, "me")
        check(leftResult.membership == Membership.LEFT && !leftResult.capabilities.send)
        check(leftResult.members.single { it.contact.id == "me" }.membership == Membership.LEFT)
        val fallbackJoin = ChatGroupActions.joinInvitation(membershipClient, invitation, "me") { true }
        check(fallbackJoin.membership == Membership.JOINED && !fallbackJoin.capabilities.send)
        refreshFails = false
        snapshotFails = false
    }
    test("failed join or decline does not claim membership changed") {
        calls.clear()
        mutationFails = true
        fails("Join denied") { ChatGroupActions.joinInvitation(membershipClient, invitation, "me") { true } }
        check(calls == listOf("join"))
        calls.clear()
        fails("Leave denied") { ChatGroupActions.leave(membershipClient, invitation, "me") }
        check(calls == listOf("leave"))
        mutationFails = false
    }
    test("invitation checks apply the configured phone policy before joining") {
        calls.clear()
        fails("allow all participants") { ChatGroupActions.joinInvitation(membershipClient, invitation, "me") { false } }
        check(calls.isEmpty())
        val unresolved = invitation.copy(members = listOf(GroupMember(Contact("peer"), Membership.JOINED)))
        // With no phone policy, an unresolved phone does not prohibit accepting an invitation.
        ChatGroupActions.joinInvitation(membershipClient, unresolved, "me") { true }
        calls.clear()
        fails("allow all participants") {
            ChatGroupActions.joinInvitation(membershipClient, unresolved, "me") { phone -> phone != null }
        }
        check(calls.isEmpty())
    }
    println("$count group tests passed")
}
