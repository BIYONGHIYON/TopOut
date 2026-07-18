# Native inference runtimes use JNI and may resolve implementation details dynamically.
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Strip application Logcat calls from optimized release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
