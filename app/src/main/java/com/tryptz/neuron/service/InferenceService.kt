package com.tryptz.neuron.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.tryptz.neuron.NeuronApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class InferenceService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, NeuronApp.CHANNEL_INFERENCE)
            .setContentTitle("Neuron")
            .setContentText("Running inference in background")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setSilent(true)
            .build()

        startForeground(2001, notification)

        if (intent?.getBooleanExtra("wake_lock", false) == true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neuron::Inference").apply {
                acquire(30 * 60 * 1000L) // 30 min max
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
