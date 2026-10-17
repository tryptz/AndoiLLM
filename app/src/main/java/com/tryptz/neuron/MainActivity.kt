package com.tryptz.neuron

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.tryptz.neuron.code.sandbox.CodeExecutor
import com.tryptz.neuron.ui.NeuronNavHost
import com.tryptz.neuron.ui.theme.NeuronTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Hilt-bound singleton ([CodeExecutorImpl]); passed down to the editor screen. */
    @Inject lateinit var codeExecutor: CodeExecutor

    /** Set when the terminal notification (or a re-launch carrying the extra) opens the app. */
    private val openTerminal = androidx.compose.runtime.mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTerminal.value = intent?.getBooleanExtra(EXTRA_OPEN_TERMINAL, false) == true

        // Android 13+ requires runtime consent for POST_NOTIFICATIONS — without
        // it every download-progress notification is silently suppressed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NeuronTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NeuronNavHost(
                        codeExecutor = codeExecutor,
                        openTerminal = openTerminal.value,
                        onTerminalOpened = { openTerminal.value = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_TERMINAL, false)) openTerminal.value = true
    }

    companion object {
        const val EXTRA_OPEN_TERMINAL = "com.tryptz.neuron.OPEN_TERMINAL"
    }
}
