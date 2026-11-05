package com.android.messaging.dumber

import android.app.Service
import android.app.job.*
import android.content.*
import android.os.IBinder
import androidx.core.content.ContextCompat
import chat.dumber.sdk.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Independent receive loops prevent a slow account from blocking another SIM. */
class ChatSyncService : Service() {
    private val executor = Executors.newScheduledThreadPool(3)
    private val receivers = ConcurrentHashMap<String, Future<*>>()
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
                        for (a in accounts) if (receivers[a.id]?.isDone != false)
                            receivers[a.id] = executor.submit { receive(a.id) }
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
                DumberChat.sync(this, a)
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (e is ChatException && e.kind == ErrorKind.AUTHENTICATION_REQUIRED) {
                    val store = ChatStore.get(this)
                    store.atomic {
                        if (store.account(id)?.session?.credentials == a.session.credentials)
                            ChatAccounts.disconnect(this, id, AccountStatus.REVOKED)
                    }
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
        executor.shutdownNow()
        ChatCalls.stop(this)
        super.onDestroy()
    }
    companion object {
        fun ensure(context: Context) {
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
