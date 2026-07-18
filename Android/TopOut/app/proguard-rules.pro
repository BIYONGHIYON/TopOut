# Native inference runtimes use JNI and may resolve implementation details dynamically.
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Release artifacts must not retain app-authored Logcat calls if one is added later.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
