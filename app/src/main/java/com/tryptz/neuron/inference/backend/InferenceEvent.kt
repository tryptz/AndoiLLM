package com.tryptz.neuron.inference.backend

/**
 * Events emitted during streaming inference.
 */
sealed class InferenceEvent {
    data class Token(val text: String, val tokSec: Float) : InferenceEvent()
    data class ThinkingToken(val text: String) : InferenceEvent()
    data object Started : InferenceEvent()
    data object Completed : InferenceEvent()
    data class Error(val message: String) : InferenceEvent()
}
