package com.android.messaging.dumber

import android.content.Context
import java.util.UUID

internal data class RemovalPrompt(val account: String, val nonce: String, val phone: String)

internal object SimRemovalChoices {
    fun observe(context: Context, sims: List<Sim>): Boolean {
        return observe(ChatStore.get(context), sims)
    }
    fun observe(store: ChatStore, sims: List<Sim>): Boolean {
        return store.atomic {
            var changed = false
            val accounts = store.accounts().filter { it.enabled }
            val choices = store.simChoices().associateBy { it.account }
            for (choice in choices.values) {
                val account = accounts.find { it.id == choice.account }
                if (account == null || sims.any { ChatRules.matches(account, it) }) {
                    store.clearSimChoice(choice.account)
                    changed = true
                }
            }
            for (account in accounts) {
                if (sims.none { ChatRules.matches(account, it) } && account.id !in choices) {
                    store.save(SimRemovalChoice(account.id, UUID.randomUUID().toString(), false))
                    changed = true
                }
            }
            changed
        }
    }
    fun pending(context: Context): List<RemovalPrompt> {
        val store = ChatStore.get(context)
        return store.atomic {
            store.simChoices().filter { !it.keep }.mapNotNull { choice ->
                store.account(choice.account)?.takeIf { it.enabled }?.let {
                    RemovalPrompt(it.id, choice.nonce, it.phone)
                }
            }
        }
    }
    fun resolve(context: Context, prompt: RemovalPrompt, keep: Boolean) {
        val store = ChatStore.get(context)
        store.atomic {
            val choice = store.simChoices().find {
                it.account == prompt.account && it.nonce == prompt.nonce && !it.keep
            } ?: return@atomic
            if (store.account(prompt.account)?.enabled != true) return@atomic
            if (keep) store.save(choice.copy(keep = true))
            else ChatAccounts.disconnect(context, prompt.account, AccountStatus.LOGGED_OUT)
        }
        DumberChat.changed(context)
    }
}
