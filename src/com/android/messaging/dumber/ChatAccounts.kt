package com.android.messaging.dumber

import android.Manifest
import android.content.Context
import android.content.RestrictionsManager
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import chat.dumber.sdk.*
import java.io.File
import java.util.Locale
import java.util.UUID

internal object ChatAccounts {
    const val SERVICE = "https://dumber.chat"
    internal var clients: (ChatConfig) -> ChatApi = { ChatClient(it) }
    fun client(context: Context, account: ChatAccount): ChatApi =
        clients(
            ChatConfig(
                SERVICE,
                account.session,
                CryptoStore(
                    File(context.noBackupFilesDir, account.storeName).absolutePath,
                    account.storePassword
                )
            )
        )
    fun client(session: Session? = null) = clients(ChatConfig(SERVICE, session))
    fun isDefault(context: Context) =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    fun normalize(
        context: Context,
        number: String,
        subscription: Int,
        countryHint: String = ""
    ): String? {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        if (Regex("\\+[1-9][0-9]{7,14}").matches(normalized)) return normalized
        val manager =
            context
                .getSystemService(TelephonyManager::class.java)
                .createForSubscriptionId(subscription)
        val region =
            countryHint.ifBlank {
                runCatching { manager.simCountryIso.ifBlank { manager.networkCountryIso } }
                    .getOrDefault("")
            }
        return if (region.isBlank()) null
        else PhoneNumberUtils.formatNumberToE164(number, region.uppercase(Locale.ROOT))
    }
    fun sims(context: Context): List<Sim>? {
        if (
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED
        )
            return null
        return runCatching {
                val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                subscriptions.activeSubscriptionInfoList?.map { info ->
                    // Match About device: this also considers carrier- and IMS-provided numbers.
                    val raw =
                        runCatching { subscriptions.getPhoneNumber(info.subscriptionId) }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: runCatching { info.number }.getOrNull()?.takeIf { it.isNotBlank() }
                                ?: runCatching {
                                    context
                                        .getSystemService(TelephonyManager::class.java)
                                        .createForSubscriptionId(info.subscriptionId)
                                        .line1Number
                                }
                                .getOrNull()
                                ?.takeIf { it.isNotBlank() }
                    val number =
                        raw?.let {
                            normalize(context, it, info.subscriptionId, info.countryIso.orEmpty())
                        }
                    Sim(
                        info.subscriptionId,
                        info.cardId,
                        info.displayName.toString(),
                        number,
                        number ?: raw
                    )
                }
                    ?: emptyList()
            }
            .getOrNull()
    }

    fun selected(context: Context, subscription: Int): Sim? {
        val id =
            if (subscription >= 0) subscription
            else SubscriptionManager.getDefaultSmsSubscriptionId()
        return sims(context)?.singleOrNull { it.subscription == id }
    }
    fun forSim(context: Context, sim: Sim?): ChatAccount? {
        if (sim == null) return null
        return ChatStore.get(context).accounts().singleOrNull {
            it.enabled && ChatRules.matches(it, sim)
        }
    }
    fun begin(context: Context, sim: Sim, phone: String): Verification {
        require(Regex("\\+[1-9][0-9]{7,14}").matches(phone)) {
            "Enter an international phone number"
        }
        val v =
            Verification(
                UUID.randomUUID().toString(),
                phone,
                sim.subscription,
                sim.cardId,
                System.currentTimeMillis(),
                System.currentTimeMillis() + 300_000
            )
        val store = ChatStore.get(context)
        store.save(v)
        try {
            client().requestCode(phone)
        } catch (e: Exception) {
            store.atomic { if (store.verification()?.nonce == v.nonce) store.clearVerification() }
            throw e
        }
        return v
    }
    fun verify(context: Context, nonce: String, code: String) {
        require(Regex("[0-9]{6}").matches(code)) { "Enter the six-digit SMS code" }
        val store = ChatStore.get(context)
        val v =
            store.verification()?.takeIf {
                it.nonce == nonce && it.expiresAt > System.currentTimeMillis()
            }
                ?: error("Request a new verification code")
        val session = client().verify(v.phone, code)
        var retained = false
        try {
            store.atomic {
                val sim =
                    selected(context, v.subscription) ?: error("The selected SIM is unavailable")
                check(
                    store.verification()?.nonce == nonce &&
                        System.currentTimeMillis() < v.expiresAt &&
                        (v.cardId < 0 || sim.cardId < 0 || v.cardId == sim.cardId)
                ) {
                    "SIM or verification changed"
                }
                val old = store.account(session.accountId)
                check(old == null || old.phone == v.phone) { "Account number changed" }
                store
                    .accounts()
                    .filter {
                        it.id != session.accountId &&
                            it.enabled &&
                            it.subscription == sim.subscription
                    }
                    .forEach {
                        store.queueLogout(it.session)
                        store.save(it.copy(status = AccountStatus.LOGGED_OUT))
                    }
                val sameDevice = old?.session?.deviceId == session.deviceId
                store.save(
                    ChatAccount(
                        session,
                        v.phone,
                        sim.subscription,
                        sim.cardId,
                        sim.number,
                        sim.name,
                        if (sameDevice) old!!.storeName else "dumber-crypto-${UUID.randomUUID()}",
                        if (sameDevice) old!!.storePassword
                        else UUID.randomUUID().toString() + UUID.randomUUID(),
                        System.currentTimeMillis()
                    )
                )
                store.clearVerification()
                store.clearSimChoice(session.accountId)
                retained = true
            }
        } finally {
            if (!retained) {
                store.queueLogout(session)
                ChatSyncService.schedule(context)
            }
        }
        ChatSyncService.ensure(context)
        ChatSyncService.schedule(context)
        ChatCalls.changed()
        DumberChat.changed(context)
        SimRemovalUi.refresh(context)
    }
    fun disconnect(context: Context, account: String, status: AccountStatus) {
        val store = ChatStore.get(context)
        store.atomic {
            val a = store.account(account) ?: return@atomic
            if (!a.enabled) return@atomic
            store.queueLogout(a.session)
            store.save(a.copy(status = status))
            store.clearSimChoice(account)
            if (store.verification()?.phone == a.phone) store.clearVerification()
        }
        ChatSyncService.schedule(context)
        ChatCalls.changed()
        DumberChat.changed(context)
        SimRemovalUi.refresh(context)
    }
    fun flushLogouts(context: Context): Boolean {
        val store = ChatStore.get(context)
        for (session in store.logouts()) try {
            client(session).logout()
            store.logoutDone(session)
        } catch (e: ChatException) {
            if (e.kind == ErrorKind.AUTHENTICATION_REQUIRED) store.logoutDone(session)
        } catch (_: Exception) {}
        return store.logouts().isEmpty()
    }
}

internal class SimMonitor {
    private var candidate: List<Sim>? = null
    private var since = 0L
    fun check(context: Context, now: Long = android.os.SystemClock.elapsedRealtime()) {
        if (now < 60_000 ||
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            candidate = null
            return
        }
        val manager = context.getSystemService(TelephonyManager::class.java)
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val sims = ChatAccounts.sims(context)
        if (sims == null) { candidate = null; return }
        val infos = runCatching { subscriptions.activeSubscriptionInfoList.orEmpty() }.getOrNull()
        if (infos == null || (0 until manager.phoneCount).any { slot ->
            val state = manager.getSimState(slot)
            state !in listOf(TelephonyManager.SIM_STATE_ABSENT, TelephonyManager.SIM_STATE_READY) ||
                (state == TelephonyManager.SIM_STATE_READY && infos.none { it.simSlotIndex == slot })
        }) { candidate = null; return }
        if (sims != candidate) { candidate = sims; since = now; return }
        if (now - since < 5000) return
        if (SimRemovalChoices.observe(context, sims)) {
            ChatCalls.changed()
        DumberChat.changed(context)
            SimRemovalUi.refresh(context)
        }
    }
}

internal object ChatPolicy {
    fun allowed(context: Context, phone: String?): Boolean {
        val r = context.getSystemService(RestrictionsManager::class.java)?.applicationRestrictions
        val mode = r?.getString("sms_filter_mode", "off") ?: "off"
        if (mode == "off") return true
        if (phone == null) return false
        fun matches(key: String) =
            r?.getString(key, "")
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it == "*" || PhoneNumberUtils.compare(it, phone) }
        return when (mode) {
            "whitelist" -> matches("sms_allowed_numbers")
            "blocklist" -> !matches("sms_blocked_numbers")
            else -> true
        }
    }
}
