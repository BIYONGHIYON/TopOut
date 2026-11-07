import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


val moveNetPoseAssetsDirectory = layout.buildDirectory.dir("generated/moveNetPoseAssets")
val moveNetPoseModelFile = moveNetPoseAssetsDirectory.map {
    it.file("models/movenet-thunder-fp16.tflite")
}
// tfhub.dev 직접 링크는 404로 종료되어 같은 파일을 제공하는 Kaggle Models 아카이브를 사용한다.
val moveNetPoseModelUrl =
    "https://www.kaggle.com/api/v1/models/google/movenet/tfLite/singlepose-thunder-tflite-float16/1/download"
val moveNetPoseArchiveEntry = "4.tflite"
// MoveNet SinglePose Thunder FP16 v4 원본(12,584,128 bytes)의 SHA-256.
val moveNetPoseModelSha256 = "41641538679ec79b07d4101e591dda47d098c09af29607674b2a40b8a3798dd3"

val moveNetPoseModelCacheFile = File(
    gradle.gradleUserHomeDir,
    "caches/topout-models/movenet-thunder-fp16-v4.tflite"
)

android {
    namespace = "com.topout.cam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.topout.cam"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "3.0"

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
    sourceSets["main"].assets.srcDir(rootProject.layout.projectDirectory.dir("../../localization"))

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
    description = "Downloads the official MoveNet SinglePose Thunder FP16 TFLite model and verifies its SHA-256."
    inputs.property("modelUrl", moveNetPoseModelUrl)
    inputs.property("modelSha256", moveNetPoseModelSha256)
    outputs.file(moveNetPoseModelFile)

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun isVerifiedModel(file: File): Boolean =
            file.isFile && sha256(file) == moveNetPoseModelSha256

        // tar.gz에서 모델 항목 하나만 꺼낸다. 다른 항목은 건너뛴다.
        fun extractTarGzEntry(archive: File, entryName: String, destination: File): Boolean {
            GZIPInputStream(archive.inputStream().buffered()).use { input ->
                val header = ByteArray(512)
                while (true) {
                    if (input.readNBytes(header, 0, header.size) < header.size) return false
                    val name = String(header, 0, 100, Charsets.US_ASCII)
                        .substringBefore('\u0000')
                        .removePrefix("./")
                    if (name.isEmpty()) return false
                    val size = String(header, 124, 12, Charsets.US_ASCII)
                        .trim('\u0000', ' ')
                        .toLong(8)
                    if (name != entryName) {
                        input.skipNBytes((size + 511) / 512 * 512)
                        continue
                    }
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = size
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) return false
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    return true
                }
            }
        }

        val output = moveNetPoseModelFile.get().asFile
        if (isVerifiedModel(output)) return@doLast

        val cache = moveNetPoseModelCacheFile
        if (!isVerifiedModel(cache)) {
            cache.parentFile.mkdirs()
            val archive = File(cache.parentFile, "${cache.name}.tar.gz.download")
            val temporary = File(cache.parentFile, "${cache.name}.download")
            archive.delete()
            temporary.delete()
            try {
                val connection = URI(moveNetPoseModelUrl).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "TopOut-Android-Gradle")
                }
                connection.getInputStream().buffered().use { input ->
                    archive.outputStream().buffered().use { outputStream -> input.copyTo(outputStream) }
                }
                require(extractTarGzEntry(archive, moveNetPoseArchiveEntry, temporary)) {
                    "MoveNet 아카이브에 $moveNetPoseArchiveEntry 항목이 없습니다."
                }
                val actual = sha256(temporary)
                require(actual == moveNetPoseModelSha256) {
                    "MoveNet Thunder FP16 SHA-256 불일치: expected=$moveNetPoseModelSha256 actual=$actual"
                }
                Files.move(
                    temporary.toPath(),
                    cache.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                archive.delete()
                temporary.delete()
            }
        }

        output.parentFile.mkdirs()
        Files.copy(
            cache.toPath(),
            output.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        require(isVerifiedModel(output)) {
            "MoveNet Thunder FP16 캐시 복사 결과의 SHA-256이 일치하지 않습니다."
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadMoveNetPoseModel)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
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

    // MobileSAM continues to use ONNX Runtime. MoveNet uses the 16 KB-page-compatible
    // LiteRT 1.x runtime while keeping the established org.tensorflow.lite Interpreter API.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.google.ai.edge.litert:litert-api:1.4.1")
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
