package io.termbridge.feature.terminal

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process, and with it every open shell, alive while the app is in the background,
 * behind an ongoing notification that says which computers are connected (ADR 0008). Started by
 * [TerminalSessions] when a session starts; stops itself once no session is running.
 */
@AndroidEntryPoint
class SessionService : Service() {
    @Inject lateinit var sessions: TerminalSessions

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false
    private var lastStartId = 0

    private data class Running(val agentId: String, val name: String, val state: SessionState)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Active sessions")
                .setDescription("Shown while a shell on your computer is open")
                .setShowBadge(false)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_END_ALL) {
            sessions.endAll() // the watcher below then stops the service
            return START_NOT_STICKY
        }
        // Must happen within seconds of startForegroundService(), whatever the sessions are doing.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(running()), foregroundType())
        if (!watching) {
            watching = true
            scope.launch { watch() }
        }
        return START_NOT_STICKY // a restarted process has no shells to keep
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun running(): List<Running> = sessions.active.value.values
        .map { Running(it.agentId, it.name, it.state.value) }
        .filter { it.state.isRunning }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch() {
        sessions.active
            .flatMapLatest { open ->
                if (open.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(open.values.map { s -> s.state.map { Running(s.agentId, s.name, it) } }) { all -> all.filter { it.state.isRunning } }
                }
            }
            .collect { running ->
                if (running.isEmpty()) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId) // unless a new session asked for the service meanwhile
                } else {
                    runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(running)) }
                }
            }
    }

    private fun notification(running: List<Running>): Notification {
        val (title, text) = when (running.size) {
            0 -> "TermBridge" to "Ending sessions…"
            1 -> running.single().let { r ->
                val one = r.state.openShells == 1
                val shells = if (one) "Your shell" else "${r.state.openShells} shells"
                when (r.state.status) {
                    is TerminalStatus.Reconnecting -> "Reconnecting to ${r.name}…" to "$shells ${if (one) "keeps" else "keep"} running on ${r.name}."
                    TerminalStatus.Connecting -> "Connecting to ${r.name}…" to "Tap to open the terminal."
                    else -> "Connected to ${r.name}" to "$shells ${if (one) "stays" else "stay"} open while you use other apps."
                }
            }
            else -> "${running.size} computers connected" to running.joinToString { it.name }
        }
        val open = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            running.singleOrNull()?.let { putExtras(TerminalDestination(it.agentId, it.name).toExtras()) }
        }
        val end = Intent(this, SessionService::class.java).setAction(ACTION_END_ALL)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, if (running.size > 1) "End all sessions" else "End session", PendingIntent.getService(this, 1, end, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private companion object {
        const val CHANNEL_ID = "sessions"
        const val NOTIFICATION_ID = 1
        const val ACTION_END_ALL = "io.termbridge.action.END_ALL"
    }
}
