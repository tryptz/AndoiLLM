package com.tryptz.neuron.terminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tryptz.neuron.MainActivity
import com.tryptz.neuron.NeuronApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that exists only while terminal sessions do. It keeps the
 * process (and therefore the shells) alive when the app is backgrounded, and
 * holds a partial wake lock when the user turns on "Keep awake" — the two
 * things people otherwise discover by losing a long `pkg upgrade`.
 */
@AndroidEntryPoint
class TerminalService : Service() {

    @Inject lateinit var manager: TerminalSessionManager
    @Inject lateinit var prefs: TerminalPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(0, false))
        scope.launch {
            prefs.keepAwake.collectLatest { on -> setWakeLock(on); refresh() }
        }
        scope.launch {
            manager.state.collectLatest { refresh() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            manager.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setWakeLock(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val n = manager.state.value.sessions.size
        val nm = ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(n, wakeLock?.isHeld == true))
    }

    @SuppressLint("WakelockTimeout") // Released when the user turns it off or exits.
    private fun setWakeLock(on: Boolean) {
        if (on && wakeLock?.isHeld != true) {
            val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neuron:terminal").apply { acquire() }
        } else if (!on) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private fun buildNotification(sessions: Int, awake: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TERMINAL, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = buildString {
            append(if (sessions == 1) "1 session" else "$sessions sessions")
            if (awake) append(" · wake lock held")
        }
        return NotificationCompat.Builder(this, NeuronApp.CHANNEL_TERMINAL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Neuron terminal")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Exit", exit)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4242
        private const val ACTION_EXIT = "com.tryptz.neuron.terminal.EXIT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TerminalService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }
}
