# Native inference runtimes use JNI and may resolve implementation details dynamically.
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

