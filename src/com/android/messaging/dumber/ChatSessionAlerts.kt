package com.android.messaging.dumber

/** Acknowledgements belong to a particular saved session, not just a phone or account ID. */
internal object ChatSessionAlerts {
    fun key(account: ChatAccount) =
        digest("${account.id}\u0000${account.session.credentials}\u0000${account.connectedAt}")

    fun pending(accounts: List<ChatAccount>): List<ChatAccount> {
        val connected = accounts.filter { it.enabled }.map { it.phone }.toSet()
        return accounts.filter { it.status == AccountStatus.REVOKED && it.phone !in connected }
    }
}
