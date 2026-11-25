package com.android.messaging.dumber

import chat.dumber.sdk.Session

fun main() {
    val revoked = ChatAccount(Session("old-token", "old-account", "device", "+46123456789"),
        "+46123456789", 3, 42, null, "SIM", "crypto", "password", 123, AccountStatus.REVOKED)
    var count = 0
    fun test(name: String, run: () -> Unit) {
        run()
        println("PASS $name")
        count++
    }
    test("previously revoked accounts alert even without connected accounts") {
        check(ChatSessionAlerts.pending(listOf(revoked)) == listOf(revoked))
    }
    test("fresh installs, manual sign-out, SIM removal and connected accounts do not alert") {
        check(ChatSessionAlerts.pending(emptyList()).isEmpty())
        for (status in listOf(AccountStatus.CONNECTED, AccountStatus.LOGGED_OUT, AccountStatus.SIM_REMOVED))
            check(ChatSessionAlerts.pending(listOf(revoked.copy(status = status))).isEmpty())
    }
    test("reconnecting with a new account ID suppresses the old phone warning") {
        val connected = revoked.copy(session = revoked.session.copy(accountId = "new-account"),
            status = AccountStatus.CONNECTED)
        check(ChatSessionAlerts.pending(listOf(revoked, connected)).isEmpty())
    }
    test("reconnecting another phone does not hide a revoked account") {
        check(ChatSessionAlerts.pending(listOf(revoked,
            revoked.copy(phone = "+46987654321", status = AccountStatus.CONNECTED))) == listOf(revoked))
    }
    test("acknowledgements survive reload but do not hide a later session revocation") {
        val key = ChatSessionAlerts.key(revoked)
        check(key == ChatSessionAlerts.key(revoked.copy()))
        check(key != ChatSessionAlerts.key(revoked.copy(session = revoked.session.copy(credentials = "new-token"))))
        check(key != ChatSessionAlerts.key(revoked.copy(connectedAt = 456)))
    }
    println("$count session alert tests passed")
}
