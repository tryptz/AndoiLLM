package com.tryptz.neuron.domain.model

enum class InferenceBackend(val label: String, val desc: String) {
    NPU("Fast", "Hexagon NPU — fastest, best battery"),
    GPU("Quality", "Adreno GPU via Vulkan — larger models"),
    CPU("Compatible", "CPU NEON SIMD — any GGUF"),
    AUTO("Auto", "Best backend for this model")
}

enum class Quantization(val label: String, val bpw: Float) {
    INT2("INT2", 2f), INT4("INT4", 4f), Q4_0("Q4_0", 4.5f),
    Q4_K_M("Q4_K_M", 4.83f), INT8("INT8", 8f), Q8_0("Q8_0", 8.5f),
    FP16("FP16", 16f)
}

enum class ThermalPolicy { PAUSE, SLOW_DOWN, IGNORE }
enum class ReasoningEffort { NONE, LOW, MEDIUM, HIGH }
enum class SettingsLevel { BASIC, INTERMEDIATE, EXPERT }
enum class ModelArchitecture { DENSE, MOE, HYBRID }
enum class MessageRole { SYSTEM, USER, ASSISTANT }
enum class ThermalState { NOMINAL, WARM, HOT, CRITICAL }

enum class CodeLanguage(val displayName: String, val extension: String) {
    JAVASCRIPT("JavaScript", "js"),
    PYTHON("Python", "py"),
    BASH("Bash", "sh"),
    HTML("HTML", "html"),
    UNKNOWN("Text", "txt")
}

enum class ErrorType { SYNTAX, RUNTIME, TIMEOUT, MEMORY, SECURITY }
