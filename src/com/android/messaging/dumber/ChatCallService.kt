package com.android.messaging.dumber

import android.app.Service
import android.content.*
import android.os.*
import android.provider.BlockedNumberContract
import chat.dumber.calls.*
import chat.dumber.sdk.CallKind
import chat.dumber.sdk.ChatApi
import chat.dumber.sdk.CallSignal as SdkSignal
import chat.dumber.sdk.IceCandidate as SdkCandidate

/** Account keys never leave Messaging. Every Binder entry verifies the actual caller UID. */
class ChatCallService : Service() {
    private fun authorize() {
        val expected = packageManager.getPackageUid("com.android.dialer", 0)
        if (Binder.getCallingUid() != expected) throw SecurityException("Dialer only")
    }
    private fun account(id: String): ChatAccount {
        check(ChatAccounts.isDefault(this) && ChatCalls.enabled(this)) { "Calling disabled" }
        return ChatStore.get(this).account(id)?.takeIf { it.enabled } ?: error("Account disconnected")
    }
    private fun allowed(phone: String) {
        check(ChatPolicy.allowed(this, phone) && !BlockedNumberContract.isBlocked(this, phone)) { "Number blocked" }
    }
    private fun client(a: ChatAccount): ChatApi = ChatAccounts.client(this, a)
    private val binder = object : ICallBroker.Stub() {
        override fun version(): Int { authorize(); return 1 }
        override fun accounts(): Array<CallAccount> {
            authorize()
            if (!ChatAccounts.isDefault(this@ChatCallService) || !ChatCalls.enabled(this@ChatCallService)) return emptyArray()
            return ChatStore.get(this@ChatCallService).accounts().filter { it.enabled }.map { a -> CallAccount().apply {
                id=a.id; phone=a.phone; subscription=a.subscription; partyId=a.session.deviceId
            } }.toTypedArray()
        }
        override fun subscribe(listener: ICallListener) { authorize(); ChatCalls.listeners.register(listener) }
        override fun supportsCall(accountId: String, phone: String): Boolean {
            authorize(); val a=account(accountId); val number=ChatAccounts.normalize(this@ChatCallService, phone, a.subscription) ?: error("Invalid number")
            allowed(number); return client(a).callCapability(number)
        }
        override fun prepare(accountId: String, phone: String, conversationId: String?): CallSetup {
            authorize(); val a=account(accountId); val api=client(a)
            val number=ChatAccounts.normalize(this@ChatCallService, phone, a.subscription) ?: error("Invalid number")
            allowed(number)
            val conversation=if (conversationId.isNullOrEmpty()) api.openChat(number) else api.conversationInfo(conversationId)
            check(conversation.participants.singleOrNull()?.phone == number) { "Wrong peer" }
            val end=SystemClock.elapsedRealtime()+12_000
            while (api.conversationInfo(conversation.id).members.count { it.membership == chat.dumber.sdk.Membership.JOINED } != 2) {
                check(SystemClock.elapsedRealtime()<end) { "Recipient not ready" }; Thread.sleep(150)
                check(account(accountId).session.deviceId == a.session.deviceId) { "Account replaced" }
            }
            val turn=api.turnConfig()
            check(account(accountId).session.deviceId == a.session.deviceId) { "Account replaced" }
            return CallSetup().apply { this.conversationId=conversation.id; uris=turn.uris.toTypedArray(); username=turn.username; password=turn.password; ttl=turn.ttl }
        }
        override fun send(accountId: String, conversationId: String, signal: CallSignal, transactionId: String) {
            authorize(); val a=account(accountId); val api=client(a)
            check(signal.partyId == a.session.deviceId) { "Wrong device" }
            val peer=api.conversationInfo(conversationId).participants.singleOrNull()?.phone ?: error("Unknown peer")
            allowed(peer)
            val candidates=signal.candidates ?: emptyArray(); val mids=signal.mids ?: emptyArray(); val lines=signal.lines ?: intArrayOf()
            check(candidates.size<=32 && candidates.size==mids.size && mids.size==lines.size)
            val data=SdkSignal(CallKind.valueOf(signal.kind),signal.callId,signal.partyId,signal.sentAtMs,
                signal.sdp,signal.descriptionType,candidates.indices.map { SdkCandidate(candidates[it],mids[it],lines[it]) },signal.selectedPartyId,signal.reason)
            api.sendCall(conversationId,data,transactionId)
        }
        override fun acknowledge(accountId: String, eventId: String) {
            authorize(); client(account(accountId)).acknowledgeCalls(listOf(eventId))
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
}

internal object ChatCalls {
    val listeners=RemoteCallbackList<ICallListener>()
    private var wakeContext: Context?=null
    private var wakeBound=false
    private var wake: ICallWake?=null
    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { wake=ICallWake.Stub.asInterface(service); runCatching { wake?.wake() } }
        override fun onServiceDisconnected(name: ComponentName) { wake=null }
        override fun onBindingDied(name: ComponentName) { synchronized(ChatCalls) { wakeContext?.let { stop(it); ensure(it) } } }
    }
    fun enabled(context: Context) = context.getSharedPreferences("dumber_calls",0).getBoolean("enabled",false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("dumber_calls",0).edit().putBoolean("enabled",enabled).apply()
        DumberChat.workers.execute { ChatStore.get(context).accounts().filter { it.enabled }.forEach { a ->
            runCatching { ChatAccounts.client(context,a).setCallCapability(enabled && available(context)) }
        }; changed() }
        if (enabled) ensure(context) else stop(context)
    }
    fun available(context: Context): Boolean = runCatching {
        val info=context.packageManager.getServiceInfo(ComponentName("com.android.dialer","com.android.dialer.dumber.CallWakeService"),0)
        info.enabled && info.applicationInfo.enabled
    }.getOrDefault(false)
    @Synchronized fun ensure(context: Context) {
        if (!enabled(context) || !available(context)) return
        wakeContext=context.applicationContext
        if (!wakeBound) wakeBound=context.applicationContext.bindService(Intent().setComponent(ComponentName("com.android.dialer","com.android.dialer.dumber.CallWakeService")),connection,Context.BIND_AUTO_CREATE)
        else runCatching { wake?.wake() }
    }
    @Synchronized fun stop(context: Context) {
        if (wakeBound) runCatching { context.applicationContext.unbindService(connection) }
        wakeBound=false; wake=null; wakeContext=null; changed()
    }
    @Synchronized fun changed() { val n=listeners.beginBroadcast(); try { for(i in 0 until n) runCatching { listeners.getBroadcastItem(i).onAccountsChanged() } } finally { listeners.finishBroadcast() } }
    @Synchronized fun receive(context: Context, account: ChatAccount, events: List<chat.dumber.sdk.CallEvent>) {
        if(events.isEmpty()) return
        ensure(context)
        val api=ChatAccounts.client(context,account)
        for(e in events) {
            val phone=e.sender.phone
            if (phone==null && enabled(context)) continue // Retry after directory resolution.
            if (!enabled(context) || phone==null || !ChatPolicy.allowed(context,phone) || BlockedNumberContract.isBlocked(context,phone)) {
                api.acknowledgeCalls(listOf(e.eventId)); continue
            }
            val s=e.signal
            val event=CallEvent().apply { accountId=account.id; conversationId=e.conversationId; this.phone=phone; eventId=e.eventId; expiresAtMs=e.expiresAtMs
                signal=CallSignal().apply { kind=s.kind.name; callId=s.callId; partyId=s.partyId; sentAtMs=s.sentAtMs; sdp=s.sdp; descriptionType=s.descriptionType; selectedPartyId=s.selectedPartyId; reason=s.reason
                    candidates=s.candidates.map { it.candidate }.toTypedArray(); mids=s.candidates.map { it.sdpMid }.toTypedArray(); lines=s.candidates.map { it.sdpMLineIndex }.toIntArray() }
            }
            val n=listeners.beginBroadcast(); try { for(i in 0 until n) runCatching { listeners.getBroadcastItem(i).onEvent(event) } } finally { listeners.finishBroadcast() }
        }
    }
}
