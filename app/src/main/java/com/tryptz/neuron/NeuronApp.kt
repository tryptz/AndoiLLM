package com.tryptz.neuron

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class NeuronApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(NeuronTree())
            Timber.tag("Neuron.App").i("[op=app_start] version=${BuildConfig.VERSION_NAME} debug=true")
        }
        createNotificationChannels()
    }

    /**
     * Custom Timber tree that prefixes every auto-derived class-name tag with
     * `Neuron.` — so `adb logcat | grep Neuron` captures the entire Kotlin side
     * of the app while the native side already logs as `NeuronInference` /
     * `LlamaCpp`. One grep keyword sees the whole stack.
     */
    private class NeuronTree : Timber.DebugTree() {
        override fun createStackElementTag(element: StackTraceElement): String {
            return "Neuron." + (super.createStackElementTag(element) ?: "App")
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, "Model Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_INFERENCE, "Inference", NotificationManager.IMPORTANCE_MIN))
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_INFERENCE = "inference"
    }
}
