# Native inference runtimes use JNI and may resolve implementation details dynamically.
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**
