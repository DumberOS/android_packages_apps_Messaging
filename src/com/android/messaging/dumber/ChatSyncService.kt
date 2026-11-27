package com.android.messaging.dumber

import android.app.Service
import android.app.job.*
import android.content.*
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.database.ContentObserver
import android.provider.Telephony
import android.provider.ContactsContract
import android.util.Log
import com.android.messaging.datamodel.MessagingContentProvider
import androidx.core.content.ContextCompat
import chat.dumber.sdk.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Independent receive loops prevent a slow account from blocking another SIM. */
class ChatSyncService : Service() {
    private val executor = Executors.newScheduledThreadPool(3)
    private val receivers = ConcurrentHashMap<String, Future<*>>()
    private val publishers = Executors.newScheduledThreadPool(2)
    private val publishJobs = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val refreshes = ConcurrentHashMap<String, ChatSmsRefresh>()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshes.values.forEach { it.changed() } }
    }
    override fun onCreate() {
        super.onCreate()
        // Bugle owns outgoing status; the system provider owns received SMS.
        for (uri in listOf(Telephony.Sms.CONTENT_URI, MessagingContentProvider.CONVERSATION_MESSAGES_URI,
            ContactsContract.Contacts.CONTENT_URI)) {
            // Contacts permission may be declined; the periodic scan still runs.
            runCatching { contentResolver.registerContentObserver(uri, true, observer) }
        }
    }
    private val running = AtomicBoolean(false)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(8090, ChatNotifications.connection(this))
        if (running.compareAndSet(false, true)) {
            val monitor = SimMonitor()
            executor.scheduleWithFixedDelay(
                {
                    try {
                        if (!ChatAccounts.isDefault(this)) {
                            stopSelf()
                            return@scheduleWithFixedDelay
                        }
                        monitor.check(this)
                        val accounts = ChatStore.get(this).accounts().filter { it.enabled }
                        if (accounts.isEmpty()) {
                            stopSelf()
                            return@scheduleWithFixedDelay
                        }
                        for (id in publishJobs.keys) if (accounts.none { it.id == id }) {
                            publishJobs.remove(id)?.cancel(true)
                            refreshes.remove(id)
                        }
                        for (a in accounts) {
                            if (receivers[a.id]?.isDone != false) receivers[a.id] = executor.submit { receive(a.id) }
                            if (publishJobs[a.id]?.isDone != false) {
                                val refresh = ChatSmsRefresh()
                                refreshes[a.id] = refresh
                                publishJobs[a.id] = publishers.scheduleWithFixedDelay({ publish(a.id, refresh) }, 0, 1, TimeUnit.SECONDS)
                            }
                        }
                    } catch (_: Exception) {
                        /* Temporary telephony/storage failures must not kill the timer. */
                    }
                },
                0,
                1,
                TimeUnit.SECONDS
            )
        }
        return START_STICKY
    }
    private fun publish(id: String, refresh: ChatSmsRefresh) {
        if (!running.get() || !ChatAccounts.isDefault(this)) return
        val ticket = refresh.due(SystemClock.elapsedRealtime()) ?: return
        try {
            val account = ChatStore.get(this).account(id)?.takeIf { it.enabled } ?: return
            val started = SystemClock.elapsedRealtime()
            ChatSmsSync.publish(this, account)
            refresh.complete(ticket, SystemClock.elapsedRealtime())
            if (ChatStore.get(this).smsSnapshot(account)?.finished == false) refresh.changed()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed > 5_000) Log.i("ChatSmsSync", "SMS export took ${elapsed}ms")
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            // No message bodies, contact details, or credentials in diagnostics.
            Log.w("ChatSmsSync", "SMS export interrupted: ${e.javaClass.simpleName}")
        }
    }
    private fun receive(id: String) {
        var nextStatus = 0L
        while (
            running.get() && !Thread.currentThread().isInterrupted && ChatAccounts.isDefault(this)
        ) {
            val a = ChatStore.get(this).account(id)?.takeIf { it.enabled } ?: break
            try {
                if (System.currentTimeMillis() >= nextStatus) {
                    ChatAccounts.client(this, a).sessionStatus()
                    runCatching { ChatAccounts.client(this, a).setCallCapability(ChatCalls.enabled(this) && ChatCalls.available(this)) }
                    ChatCalls.ensure(this)
                    ChatCalls.changed()
                    nextStatus = System.currentTimeMillis() + 60_000
                }
                ChatBrowserHistory.retry(this, a)
                DumberChat.sync(this, a)
                refreshes[id]?.changed()
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (e is ChatException && e.kind == ErrorKind.AUTHENTICATION_REQUIRED) {
                    ChatAccounts.disconnect(this, id, AccountStatus.REVOKED, a.session.credentials)
                    break
                }
                nextStatus = 0
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }
    override fun onDestroy() {
        running.set(false)
        contentResolver.unregisterContentObserver(observer)
        publishers.shutdownNow()
        executor.shutdownNow()
        ChatCalls.stop(this)
        super.onDestroy()
    }
    companion object {
        fun ensure(context: Context) {
            // Also discover revocations recorded by an older app, when no receiver can start.
            ChatNotifications.sessionEnded(context)
            if (
                ChatAccounts.isDefault(context) &&
                    ChatStore.get(context).accounts().any { it.enabled } &&
                    ChatClient.isAvailable()
            )
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ChatSyncService::class.java)
                    )
                }
        }
        fun schedule(context: Context) {
            if (ChatStore.get(context).logouts().isEmpty()) return
            context
                .getSystemService(JobScheduler::class.java)
                .schedule(
                    JobInfo.Builder(8091, ComponentName(context, ChatLogoutJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build()
                )
        }
    }
}

class ChatLogoutJob : JobService() {
    private var job: Future<*>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job =
            DumberChat.workers.submit {
                val done = runCatching { ChatAccounts.flushLogouts(this) }.getOrDefault(false)
                jobFinished(params, !done)
            }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel(true)
        return true
    }
}
