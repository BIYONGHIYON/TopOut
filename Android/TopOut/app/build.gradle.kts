import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


val moveNetPoseAssetsDirectory = layout.buildDirectory.dir("generated/moveNetPoseAssets")
val moveNetPoseModelFile = moveNetPoseAssetsDirectory.map {
    it.file("models/movenet-lightning-fp16.tflite")
}
val moveNetPoseModelUrl =
    "https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/float16/4?lite-format=tflite"

android {
    namespace = "com.topout.cam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.topout.cam"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
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
        noCompress += setOf("onnx", "tflite")
    }

    sourceSets["main"].assets.srcDir(moveNetPoseAssetsDirectory)

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
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

val downloadMoveNetPoseModel by tasks.registering {
    group = "topout models"
    description = "Downloads and validates the official MoveNet SinglePose Lightning FP16 TFLite model."
    inputs.property("modelUrl", moveNetPoseModelUrl)
    outputs.file(moveNetPoseModelFile)

    doLast {
        fun isValidTflite(file: File): Boolean {
            if (!file.isFile || file.length() < 1_000_000L) return false
            val header = ByteArray(8)
            file.inputStream().use { input ->
                if (input.read(header) != header.size) return false
            }
            return String(header, 4, 4, Charsets.US_ASCII) == "TFL3"
        }

        val output = moveNetPoseModelFile.get().asFile
        if (isValidTflite(output)) return@doLast

        output.parentFile.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.download")
        temporary.delete()
        val connection = URI(moveNetPoseModelUrl).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "TopOut-Android-Gradle")
        }
        connection.getInputStream().buffered().use { input ->
            temporary.outputStream().buffered().use { outputStream -> input.copyTo(outputStream) }
        }
        require(isValidTflite(temporary)) {
            "MoveNet Lightning FP16 다운로드 결과가 유효한 TFLite FlatBuffer가 아닙니다."
        }
        Files.move(
            temporary.toPath(),
            output.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadMoveNetPoseModel)
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

    // MobileSAM continues to use ONNX Runtime; pose inference uses MoveNet through TensorFlow Lite.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
