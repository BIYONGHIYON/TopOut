package com.topout.cam.detection

import android.content.Context
import android.graphics.Bitmap
import com.topout.cam.model.TopOutConfig
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import kotlin.math.floor

/** MoveNet SinglePose Lightning FP16 inference with temporal smart cropping. */
internal class MoveNetLightningPoseDetector(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val inputSize = TopOutConfig.MoveNetPoseInputSize
    private val inputBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * RGB_CHANNELS)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer
        .allocateDirect(PoseLandmark.COUNT * OUTPUT_VALUES_PER_KEYPOINT * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputFloatBuffer = outputBuffer.asFloatBuffer()
    private val outputValues = FloatArray(PoseLandmark.COUNT * OUTPUT_VALUES_PER_KEYPOINT)
    private val sampleX = IntArray(inputSize)
    private val sampleY = IntArray(inputSize)

    private var sourcePixels = IntArray(0)
    private var interpreter: Interpreter? = null
    private var previousCropRegion: MoveNetCropRegion? = null
    private var cachedInitialCropRegion: MoveNetCropRegion? = null
    private var cachedImageWidth = 0
    private var cachedImageHeight = 0
    @Volatile private var closed = false

    @Synchronized
    fun detect(bitmap: Bitmap, useTrackingAssociation: Boolean): PoseResult? {
        check(!closed) { "MoveNet 실행기가 이미 종료되었습니다." }
        require(bitmap.width > 0 && bitmap.height > 0) { "포즈 분석 이미지가 올바르지 않습니다." }

        val runtime = ensureInterpreterLocked()
        val initialCrop = initialCropRegion(bitmap.width, bitmap.height)
        val crop = if (useTrackingAssociation) previousCropRegion ?: initialCrop else initialCrop
        prepareInput(bitmap, crop)
        outputBuffer.clear()
        runtime.run(inputBuffer, outputBuffer)
        outputFloatBuffer.rewind()
        outputFloatBuffer.get(outputValues)

        val pose = MoveNetPoseOutputParser.parse(outputValues, crop)
        previousCropRegion = if (pose != null && useTrackingAssociation) {
            MoveNetPoseOutputParser.nextCropRegion(
                pose = pose,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                minimumKeypointScore = TopOutConfig.MoveNetCropKeypointConfidence
            )
        } else {
            null
        }
        return pose
    }

    @Synchronized
    fun resetTracking() {
        previousCropRegion = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        previousCropRegion = null
        cachedInitialCropRegion = null
        sourcePixels = IntArray(0)
        runCatching { interpreter?.close() }
        interpreter = null
    }

    private fun initialCropRegion(width: Int, height: Int): MoveNetCropRegion {
        val cached = cachedInitialCropRegion
        if (cached != null && width == cachedImageWidth && height == cachedImageHeight) return cached
        return MoveNetPoseOutputParser.initialCropRegion(width, height).also {
            cachedImageWidth = width
            cachedImageHeight = height
            cachedInitialCropRegion = it
        }
    }

    private fun ensureInterpreterLocked(): Interpreter {
        interpreter?.let { return it }
        val model = readModelBuffer()
        val options = Interpreter.Options().apply {
            setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
            setUseXNNPACK(true)
        }
        val created = Interpreter(model, options)
        try {
            val inputTensor = created.getInputTensor(0)
            val outputTensor = created.getOutputTensor(0)
            check(
                inputTensor.dataType() == DataType.UINT8 && inputTensor.shape().contentEquals(
                    intArrayOf(1, inputSize, inputSize, RGB_CHANNELS)
                )
            ) {
                "MoveNet 입력 텐서 계약이 예상과 다릅니다: ${inputTensor.dataType()} ${inputTensor.shape().contentToString()}"
            }
            check(
                outputTensor.dataType() == DataType.FLOAT32 && outputTensor.shape().contentEquals(
                    intArrayOf(1, 1, PoseLandmark.COUNT, OUTPUT_VALUES_PER_KEYPOINT)
                )
            ) {
                "MoveNet 출력 텐서 계약이 예상과 다릅니다: ${outputTensor.dataType()} ${outputTensor.shape().contentToString()}"
            }
        } catch (error: Throwable) {
            created.close()
            throw error
        }
        interpreter = created
        return created
    }

    /** Uses mmap for uncompressed assets and falls back to a direct buffer for unusual package layouts. */
    private fun readModelBuffer(): ByteBuffer {
        val candidates = arrayOf(MODEL_ASSET, MODEL_ASSET.removePrefix("models/"))
        var lastError: Throwable? = null
        for (asset in candidates) {
            try {
                appContext.assets.openFd(asset).use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                        return channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            descriptor.startOffset,
                            descriptor.declaredLength
                        ).order(ByteOrder.nativeOrder())
                    }
                }
            } catch (error: Throwable) {
                if (error !is FileNotFoundException) lastError = error
            }
            try {
                val bytes = appContext.assets.open(asset).use { it.readBytes() }
                return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                    put(bytes)
                    rewind()
                }
            } catch (error: FileNotFoundException) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "MoveNet Lightning FP16 모델을 불러오지 못했습니다. " +
                "Gradle Sync 또는 downloadMoveNetPoseModel 작업이 완료되었는지 확인해주세요.",
            lastError
        )
    }

    private fun prepareInput(bitmap: Bitmap, crop: MoveNetCropRegion) {
        val requiredPixels = bitmap.width * bitmap.height
        if (sourcePixels.size < requiredPixels) sourcePixels = IntArray(requiredPixels)
        bitmap.getPixels(sourcePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (targetY in 0 until inputSize) {
            val normalizedY = crop.yMin + (targetY + HALF_PIXEL) / inputSize * crop.height
            sampleY[targetY] = floor(normalizedY * bitmap.height).toInt()
        }
        for (targetX in 0 until inputSize) {
            val normalizedX = crop.xMin + (targetX + HALF_PIXEL) / inputSize * crop.width
            sampleX[targetX] = floor(normalizedX * bitmap.width).toInt()
        }

        inputBuffer.clear()
        for (targetY in 0 until inputSize) {
            val sourceY = sampleY[targetY]
            for (targetX in 0 until inputSize) {
                val sourceX = sampleX[targetX]
                val color = if (sourceX in 0 until bitmap.width && sourceY in 0 until bitmap.height) {
                    sourcePixels[sourceY * bitmap.width + sourceX]
                } else {
                    0
                }
                inputBuffer.put((color ushr 16).toByte())
                inputBuffer.put((color ushr 8).toByte())
                inputBuffer.put(color.toByte())
            }
        }
        inputBuffer.rewind()
    }

    private companion object {
        const val MODEL_ASSET = "models/movenet-lightning-fp16.tflite"
        const val RGB_CHANNELS = 3
        const val OUTPUT_VALUES_PER_KEYPOINT = 3
        const val HALF_PIXEL = 0.5f
    }
}
