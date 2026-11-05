package com.android.messaging.dumber

import chat.dumber.sdk.*
import java.lang.reflect.Proxy

private fun api(block: (String, Array<out Any?>) -> Any?): ChatApi =
    Proxy.newProxyInstance(ChatApi::class.java.classLoader, arrayOf(ChatApi::class.java)) {
        _,
        method,
        args ->
        block(method.name, args ?: emptyArray())
    } as ChatApi

private val account =
    ChatAccount(
        Session("opaque", "a", "device", null),
        "+46123456789",
        3,
        42,
        null,
        "SIM",
        "store",
        "password",
        0
    )
private val pending = PendingText("stable-txn", "a", 3, "+46987654321", "hello", "thread")

private fun failure(block: () -> Unit) {
    check(runCatching(block).isFailure)
}

fun main() {
    var count = 0
    fun test(name: String, run: () -> Unit) {
        run()
        println("PASS $name")
        count++
    }
    test("unknown availability never silently selects carrier") {
        check(!ChatRules.useCarrier(null))
        check(!ChatRules.useCarrier(RecipientStatus(Availability.UNKNOWN)))
        check(!ChatRules.useCarrier(RecipientStatus(Availability.AVAILABLE)))
        check(ChatRules.useCarrier(RecipientStatus(Availability.UNAVAILABLE)))
    }
    test("timeout persists uncertainty and retry reuses the transaction and room") {
        var saved = pending
        var sends = 0
        var opens = 0
        val client = api { name, args ->
            when (name) {
                "openChat" -> {
                    check(args[1] == true)
                    opens++
                    Conversation("room")
                }
                "sendText" -> {
                    check(saved.state == DeliveryState.UNCERTAIN)
                    check(args[2] == "stable-txn")
                    sends++
                    if (sends == 1) throw ChatException("offline", ErrorKind.TRANSPORT_FAILURE)
                    SentMessage("event", true)
                }
                else -> error(name)
            }
        }
        failure { ChatDelivery.transmit(saved, account, client, { saved = it }) { true } }
        check(saved.state == DeliveryState.UNCERTAIN)
        ChatDelivery.transmit(saved, account, client, { saved = it }) { true }
        check(
            saved.state == DeliveryState.CONFIRMED &&
                saved.eventId == "event" &&
                sends == 2 &&
                opens == 1
        )
    }
    test("confirmed messages reconcile without a network send") {
        val p = pending.copy(state = DeliveryState.CONFIRMED, eventId = "e", conversation = "room")
        check(
            ChatDelivery.transmit(
                p,
                account.copy(status = AccountStatus.LOGGED_OUT),
                api { n, _ -> error(n) },
                { error("write") }
            ) {
                false
            } == p
        )
    }
    test("a definitive failure cannot erase an earlier uncertain handoff") {
        for (uncertain in listOf(false, true)) {
            var saved =
                pending.copy(
                    conversation = "room",
                    state = if (uncertain) DeliveryState.UNCERTAIN else DeliveryState.PENDING
                )
            failure {
                ChatDelivery.transmit(
                    saved,
                    account,
                    api { _, _ -> throw ChatException("gone", ErrorKind.RECIPIENT_UNAVAILABLE) },
                    { saved = it }
                ) {
                    true
                }
            }
            check(saved.state == if (uncertain) DeliveryState.UNCERTAIN else DeliveryState.PENDING)
        }
    }
    test("missing encryption confirmation never records success") {
        var saved = pending.copy(conversation = "room")
        failure {
            ChatDelivery.transmit(
                saved,
                account,
                api { _, _ -> SentMessage("e", false) },
                { saved = it }
            ) {
                true
            }
        }
        check(saved.state == DeliveryState.UNCERTAIN && saved.eventId == null)
    }
    test("account and device changes cannot resend an old uncertain message") {
        val client = api { n, _ -> error("network should not run: $n") }
        failure {
            ChatDelivery.transmit(
                pending,
                account.copy(status = AccountStatus.REVOKED),
                client,
                { error("write") }
            ) {
                true
            }
        }
        failure {
            ChatDelivery.transmit(
                pending.copy(account = "other"),
                account,
                client,
                { error("write") }
            ) {
                true
            }
        }
        failure {
            ChatDelivery.transmit(
                pending.copy(deviceId = "old", state = DeliveryState.UNCERTAIN),
                account,
                client,
                { error("write") }
            ) {
                true
            }
        }
    }
    test("groups validate membership capabilities and every participant before sending") {
        val p = pending.copy(group = true, conversation = "group")
        for (info in
            listOf(
                Conversation("group", ConversationKind.DIRECT),
                Conversation("group", ConversationKind.GROUP, membership = Membership.INVITED),
                Conversation(
                    "group",
                    ConversationKind.GROUP,
                    membership = Membership.JOINED,
                    capabilities = GroupCapabilities(send = true),
                    members = listOf(GroupMember(Contact("unknown"), Membership.JOINED))
                )
            )) {
            var sends = 0
            failure {
                ChatDelivery.transmit(
                    p,
                    account,
                    api { name, _ ->
                        if (name == "conversationInfo") info
                        else {
                            sends++
                            SentMessage("e", true)
                        }
                    },
                    {}
                ) {
                    true
                }
            }
            check(sends == 0)
        }
    }
    test("SIM identity detects replacement without rejecting missing reported numbers") {
        check(ChatRules.matches(account, Sim(3, 42, "SIM", null)))
        check(!ChatRules.matches(account, Sim(3, 43, "SIM", null)))
        check(!ChatRules.matches(account, Sim(4, 42, "SIM", null)))
        check(
            !ChatRules.matches(
                account.copy(reportedNumber = "+46111111111"),
                Sim(3, 42, "SIM", "+46222222222")
            )
        )
    }
    test("verification parser rejects ambiguous and unrelated SMS codes") {
        check(ChatRules.verificationCode("Your verification code is 123456") == "123456")
        check(ChatRules.verificationCode("code 123456 or 654321") == null)
        check(ChatRules.verificationCode("parcel 123456") == null)
        check(ChatRules.verificationCode("code 1234567") == null)
    }
    println("$count tests passed")
}
