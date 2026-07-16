package com.topout.cam.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.topout.cam.model.TopOutConfig
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** On-device CPU inference wrapper for the bundled YOLO11n-pose ONNX model. */
internal class Yolo11NanoPoseDetector(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private var sessionOptions: OrtSession.SessionOptions? = null
    private var session: OrtSession? = null
    private var previousPersonBox: androidx.compose.ui.geometry.Rect? = null
    @Volatile private var closed = false

    @Synchronized
    fun detect(bitmap: Bitmap, useTrackingAssociation: Boolean): YoloPoseResult? {
        check(!closed) { "YOLO11n-Pose 실행기가 이미 종료되었습니다." }
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "포즈 분석 이미지가 올바르지 않습니다."
        }
        val activeSession = ensureSessionLocked()
        val preprocessed = preprocess(bitmap)
        val inputName = activeSession.inputInfo.keys.firstOrNull()
            ?: error("YOLO11n-Pose 입력 텐서를 찾지 못했습니다.")

        OnnxTensor.createTensor(environment, preprocessed.buffer, preprocessed.shape).use { input ->
            activeSession.run(mapOf(inputName to input)).use { output ->
                val outputName = activeSession.outputInfo.keys.firstOrNull()
                    ?: error("YOLO11n-Pose 출력 텐서를 찾지 못했습니다.")
                val tensor = output.get(outputName).orElseThrow {
                    IllegalStateException("YOLO11n-Pose 출력 '$outputName'을 읽지 못했습니다.")
                } as? OnnxTensor ?: error("YOLO11n-Pose 출력이 텐서가 아닙니다.")
                val candidates = Yolo11PoseOutputParser.parse(
                    data = tensor.copyFloatData(),
                    shape = tensor.info.shape,
                    transform = preprocessed.transform,
                    personConfidenceThreshold = TopOutConfig.YoloPosePersonConfidence,
                    nmsIouThreshold = TopOutConfig.YoloPoseNmsIou
                )
                val selected = Yolo11PoseOutputParser.selectPrimary(
                    candidates = candidates,
                    previousBox = previousPersonBox,
                    useTrackingAssociation = useTrackingAssociation
                )
                previousPersonBox = selected?.boundingBox
                return selected
            }
        }
    }

    @Synchronized
    fun resetTracking() {
        previousPersonBox = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        previousPersonBox = null
        runCatching { session?.close() }
        runCatching { sessionOptions?.close() }
        session = null
        sessionOptions = null
    }

    private fun ensureSessionLocked(): OrtSession {
        session?.let { return it }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        try {
            val created = environment.createSession(readModelBytes(), options)
            sessionOptions = options
            session = created
            return created
        } catch (error: Throwable) {
            runCatching { options.close() }
            throw IllegalStateException(
                "YOLO11n-Pose 모델을 불러오지 못했습니다. " +
                    "Gradle Sync 또는 downloadYoloPoseModel 작업이 완료되었는지 확인해주세요.",
                error
            )
        }
    }

    private fun readModelBytes(): ByteArray {
        var lastError: Throwable? = null
        for (path in listOf("models/$MODEL_FILE_NAME", MODEL_FILE_NAME)) {
            try {
                return appContext.assets.open(path).use { it.readBytes() }
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw FileNotFoundException(
            "assets/models/$MODEL_FILE_NAME 파일을 찾지 못했습니다. " +
                lastError?.localizedMessage.orEmpty()
        )
    }

    private fun preprocess(bitmap: Bitmap): PreprocessedImage {
        val inputSize = TopOutConfig.YoloPoseInputSize
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val scale = min(inputSize.toFloat() / sourceWidth, inputSize.toFloat() / sourceHeight)
        val resizedWidth = max(1, (sourceWidth * scale).roundToInt())
        val resizedHeight = max(1, (sourceHeight * scale).roundToInt())
        val padLeft = (inputSize - resizedWidth) * 0.5f
        val padTop = (inputSize - resizedHeight) * 0.5f
        val padLeftPixels = padLeft.roundToInt()
        val padTopPixels = padTop.roundToInt()

        val sourcePixels = IntArray(sourceWidth * sourceHeight)
        bitmap.getPixels(sourcePixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
        val planeSize = inputSize * inputSize
        val normalizedPadding = 114f / 255f
        val values = FloatArray(planeSize * 3) { normalizedPadding }

        for (targetY in 0 until resizedHeight) {
            val sourceY = min(sourceHeight - 1, ((targetY + 0.5f) / scale).toInt())
            val outputY = targetY + padTopPixels
            if (outputY !in 0 until inputSize) continue
            for (targetX in 0 until resizedWidth) {
                val sourceX = min(sourceWidth - 1, ((targetX + 0.5f) / scale).toInt())
                val outputX = targetX + padLeftPixels
                if (outputX !in 0 until inputSize) continue
                val pixel = sourcePixels[sourceY * sourceWidth + sourceX]
                val index = outputY * inputSize + outputX
                values[index] = ((pixel shr 16) and 0xFF) / 255f
                values[planeSize + index] = ((pixel shr 8) and 0xFF) / 255f
                values[planeSize * 2 + index] = (pixel and 0xFF) / 255f
            }
        }

        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                rewind()
            }
        return PreprocessedImage(
            buffer = buffer,
            shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong()),
            transform = YoloLetterboxTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                inputSize = inputSize,
                scale = scale,
                padLeft = padLeftPixels.toFloat(),
                padTop = padTopPixels.toFloat()
            )
        )
    }

    private fun OnnxTensor.copyFloatData(): FloatArray {
        val duplicate = requireNotNull(floatBuffer) {
            "YOLO11n-Pose 출력 타입이 float32가 아닙니다."
        }.duplicate()
        duplicate.rewind()
        return FloatArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private data class PreprocessedImage(
        val buffer: FloatBuffer,
        val shape: LongArray,
        val transform: YoloLetterboxTransform
    )

    companion object {
        const val MODEL_FILE_NAME = "yolo11n-pose.onnx"
    }
}
