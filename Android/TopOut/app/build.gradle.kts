import java.io.BufferedInputStream
import java.net.URI
import java.net.URLConnection

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.topout.cam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.topout.cam"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += setOf("onnx", "task")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val cameraX = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    val media3 = "1.10.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")

    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


// Pose Landmarker Heavy is intentionally kept out of source control because the bundle is
// approximately 30 MB. Android builds download the pinned Google-hosted model once and then
// reuse Gradle's output cache. The app never downloads the model at runtime.
val poseHeavyModelUrl =
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/" +
        "pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task"
val poseHeavyModelFile =
    layout.projectDirectory.file("src/main/assets/models/pose_landmarker_heavy.task")

val downloadPoseHeavyModel by tasks.registering {
    group = "build setup"
    description = "Downloads the pinned MediaPipe Pose Landmarker Heavy model bundle."
    outputs.file(poseHeavyModelFile)

    doLast {
        val destination = poseHeavyModelFile.asFile
        if (destination.isFile && destination.length() >= 30_000_000L) return@doLast

        destination.parentFile.mkdirs()
        val temporary = destination.resolveSibling("${destination.name}.download")
        temporary.delete()

        val connection: URLConnection = URI(poseHeavyModelUrl).toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000

        BufferedInputStream(connection.getInputStream()).use { input ->
            temporary.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }

        check(temporary.length() >= 30_000_000L) {
            "Pose Landmarker Heavy download is incomplete: ${temporary.length()} bytes"
        }
        if (destination.exists()) check(destination.delete()) {
            "Could not replace ${destination.absolutePath}"
        }
        check(temporary.renameTo(destination)) {
            "Could not move the pose model into ${destination.absolutePath}"
        }
    }
}

// Ensure the model exists before Android packages any variant's assets.
tasks.configureEach {
    if (name == "preBuild" || (name.startsWith("merge") && name.endsWith("Assets"))) {
        dependsOn(downloadPoseHeavyModel)
    }
}
