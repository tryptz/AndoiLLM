-keep class com.tryptz.neuron.inference.bridge.** { *; }
-keep class com.tryptz.neuron.code.engine.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
