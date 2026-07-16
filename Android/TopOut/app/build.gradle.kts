import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


val yoloPoseAssetsDirectory = layout.buildDirectory.dir("generated/yoloPoseAssets")
val yoloPoseModelFile = yoloPoseAssetsDirectory.map { it.file("models/yolo11n-pose.onnx") }
val yoloPoseModelUrl =
    "https://github.com/KopalnieKrypto/cctv-gpu-engine/releases/download/" +
        "yolo11n-pose-v1.0/yolo11n-pose.onnx"
val yoloPoseModelSha256 = "70bd721f9cb797eb44cbc70bc65213397a0a26da38fe6fd5ccdf699016d33d3c"

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
        noCompress += "onnx"
    }

    sourceSets["main"].assets.srcDir(yoloPoseAssetsDirectory)

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

val downloadYoloPoseModel by tasks.registering {
    group = "topout models"
    description = "Downloads and verifies the YOLO11n-pose ONNX model used by the Android app."
    inputs.property("modelUrl", yoloPoseModelUrl)
    inputs.property("modelSha256", yoloPoseModelSha256)
    outputs.file(yoloPoseModelFile)

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val output = yoloPoseModelFile.get().asFile
        if (output.isFile && sha256(output).equals(yoloPoseModelSha256, ignoreCase = true)) {
            return@doLast
        }

        output.parentFile.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.download")
        temporary.delete()
        val connection = URI(yoloPoseModelUrl).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "TopOut-Android-Gradle")
        }
        connection.getInputStream().buffered().use { input ->
            temporary.outputStream().buffered().use { outputStream -> input.copyTo(outputStream) }
        }
        val actual = sha256(temporary)
        require(actual.equals(yoloPoseModelSha256, ignoreCase = true)) {
            "YOLO11n-pose 모델 SHA-256 불일치: expected=$yoloPoseModelSha256 actual=$actual"
        }
        Files.move(
            temporary.toPath(),
            output.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadYoloPoseModel)
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

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
