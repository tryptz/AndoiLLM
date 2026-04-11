package com.tryptz.neuron

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.tryptz.neuron.ui.NeuronNavHost
import com.tryptz.neuron.ui.theme.NeuronTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeuronTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NeuronNavHost()
                }
            }
        }
    }
}
