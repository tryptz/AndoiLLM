# UniFFI bindings (com.tryptz.neuron.core) talk to libneuron.so through JNA by
# method name: nothing in there may be renamed or stripped.
-keep class com.tryptz.neuron.core.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
