package com.topout.cam.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import com.topout.cam.model.TopOutConfig
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** MoveNet SinglePose Thunder FP16 inference with temporal smart cropping. */
internal class MoveNetThunderPoseDetector(context: Context) : AutoCloseable {
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
    private val resizedBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val resizedCanvas = Canvas(resizedBitmap)
    private val resizePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val resizeMatrix = Matrix()
    private val cropRect = RectF()
    private val inputRect = RectF(0f, 0f, inputSize.toFloat(), inputSize.toFloat())
    private val resizedPixels = IntArray(inputSize * inputSize)

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

    /**
     * Converts only the active smart-crop region from CameraX YUV_420_888 directly into the
     * 256x256 MoveNet RGB tensor. This avoids creating the intermediate 512px RGB Bitmap on every
     * tracked frame. The viewport coordinates are normalized in display orientation.
     */
    @Synchronized
    fun detectTrackedYuv(
        image: ImageProxy,
        viewportInDisplay: RectF,
        sourceWidth: Int,
        sourceHeight: Int
    ): PoseResult? {
        check(!closed) { "MoveNet 실행기가 이미 종료되었습니다." }
        require(sourceWidth > 0 && sourceHeight > 0) { "포즈 분석 영역 크기가 올바르지 않습니다." }
        require(image.width > 0 && image.height > 0 && image.planes.size >= 3) {
            "CameraX YUV 프레임이 올바르지 않습니다."
        }

        val runtime = ensureInterpreterLocked()
        val crop = previousCropRegion ?: initialCropRegion(sourceWidth, sourceHeight)
        prepareInputFromYuv(
            image = image,
            viewportInDisplay = viewportInDisplay,
            crop = crop
        )
        outputBuffer.clear()
        runtime.run(inputBuffer, outputBuffer)
        outputFloatBuffer.rewind()
        outputFloatBuffer.get(outputValues)

        val pose = MoveNetPoseOutputParser.parse(outputValues, crop)
        previousCropRegion = if (pose != null) {
            MoveNetPoseOutputParser.nextCropRegion(
                pose = pose,
                imageWidth = sourceWidth,
                imageHeight = sourceHeight,
                minimumKeypointScore = TopOutConfig.MoveNetCropKeypointConfidence
            )
        } else {
            null
        }
        return pose
    }

    /** Seeds the next inference crop only after the caller has verified real joint motion. */
    @Synchronized
    fun primeTracking(pose: PoseResult, imageWidth: Int, imageHeight: Int) {
        if (closed || imageWidth <= 0 || imageHeight <= 0) return
        previousCropRegion = MoveNetPoseOutputParser.nextCropRegion(
            pose = pose,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            minimumKeypointScore = TopOutConfig.MoveNetCropKeypointConfidence
        )
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
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { resizedBitmap.recycle() }
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
            "MoveNet Thunder FP16 모델을 불러오지 못했습니다. " +
                "Gradle Sync 또는 downloadMoveNetPoseModel 작업이 완료되었는지 확인해주세요.",
            lastError
        )
    }

    private fun prepareInput(bitmap: Bitmap, crop: MoveNetCropRegion) {
        cropRect.set(
            crop.xMin * bitmap.width,
            crop.yMin * bitmap.height,
            (crop.xMin + crop.width) * bitmap.width,
            (crop.yMin + crop.height) * bitmap.height
        )
        resizeMatrix.reset()
        check(resizeMatrix.setRectToRect(cropRect, inputRect, Matrix.ScaleToFit.FILL)) {
            "MoveNet 입력 crop 크기가 올바르지 않습니다: $crop"
        }

        // Keep padded crop areas black and use bilinear filtering instead of nearest-neighbour
        // sampling so small wrist/ankle features survive the downscale more reliably.
        resizedCanvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
        resizedCanvas.drawBitmap(bitmap, resizeMatrix, resizePaint)
        resizedBitmap.getPixels(
            resizedPixels,
            0,
            inputSize,
            0,
            0,
            inputSize,
            inputSize
        )

        inputBuffer.clear()
        for (color in resizedPixels) {
            inputBuffer.put((color ushr 16).toByte())
            inputBuffer.put((color ushr 8).toByte())
            inputBuffer.put(color.toByte())
        }
        inputBuffer.rewind()
    }

    private fun prepareInputFromYuv(
        image: ImageProxy,
        viewportInDisplay: RectF,
        crop: MoveNetCropRegion
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        val rotation = normalizedRotation(image.imageInfo.rotationDegrees)
        val rawWidth = image.width
        val rawHeight = image.height
        val chromaWidth = (rawWidth + 1) / 2
        val chromaHeight = (rawHeight + 1) / 2

        inputBuffer.clear()
        for (targetY in 0 until inputSize) {
            val sourceY = crop.yMin + crop.height * (targetY + 0.5f) / inputSize
            for (targetX in 0 until inputSize) {
                val sourceX = crop.xMin + crop.width * (targetX + 0.5f) / inputSize

                // MoveNet's square crop may extend beyond the viewport. Match resize-with-pad by
                // writing black rather than clamping those samples to an image edge.
                if (sourceX !in 0f..1f || sourceY !in 0f..1f) {
                    inputBuffer.put(0)
                    inputBuffer.put(0)
                    inputBuffer.put(0)
                    continue
                }

                val displayX = (viewportInDisplay.left + sourceX * viewportInDisplay.width())
                    .coerceIn(0f, 1f)
                val displayY = (viewportInDisplay.top + sourceY * viewportInDisplay.height())
                    .coerceIn(0f, 1f)
                val rawNormalizedX: Float
                val rawNormalizedY: Float
                when (rotation) {
                    90 -> {
                        rawNormalizedX = displayY
                        rawNormalizedY = 1f - displayX
                    }
                    180 -> {
                        rawNormalizedX = 1f - displayX
                        rawNormalizedY = 1f - displayY
                    }
                    270 -> {
                        rawNormalizedX = 1f - displayY
                        rawNormalizedY = displayX
                    }
                    else -> {
                        rawNormalizedX = displayX
                        rawNormalizedY = displayY
                    }
                }
                val rawX = rawNormalizedX * (rawWidth - 1)
                val rawY = rawNormalizedY * (rawHeight - 1)

                val yy = samplePlaneBilinear(
                    buffer = yBuffer,
                    base = yBase,
                    rowStride = yPlane.rowStride,
                    pixelStride = yPlane.pixelStride,
                    x = rawX,
                    y = rawY,
                    width = rawWidth,
                    height = rawHeight,
                    fallback = 16f
                )
                // Chroma is already half-resolution in YUV_420_888. Nearest chroma sampling
                // avoids eight extra buffer reads per model pixel while bilinear luma preserves
                // the fine edges that matter most for wrists and ankles.
                val uu = samplePlaneNearest(
                    buffer = uBuffer,
                    base = uBase,
                    rowStride = uPlane.rowStride,
                    pixelStride = uPlane.pixelStride,
                    x = rawX * 0.5f,
                    y = rawY * 0.5f,
                    width = chromaWidth,
                    height = chromaHeight,
                    fallback = 128f
                )
                val vv = samplePlaneNearest(
                    buffer = vBuffer,
                    base = vBase,
                    rowStride = vPlane.rowStride,
                    pixelStride = vPlane.pixelStride,
                    x = rawX * 0.5f,
                    y = rawY * 0.5f,
                    width = chromaWidth,
                    height = chromaHeight,
                    fallback = 128f
                )

                val c = max(0f, yy - 16f)
                val d = uu - 128f
                val e = vv - 128f
                val red = (1.164f * c + 1.596f * e).roundToInt().coerceIn(0, 255)
                val green = (1.164f * c - 0.392f * d - 0.813f * e).roundToInt().coerceIn(0, 255)
                val blue = (1.164f * c + 2.017f * d).roundToInt().coerceIn(0, 255)
                inputBuffer.put(red.toByte())
                inputBuffer.put(green.toByte())
                inputBuffer.put(blue.toByte())
            }
        }
        inputBuffer.rewind()
    }

    private fun samplePlaneNearest(
        buffer: ByteBuffer,
        base: Int,
        rowStride: Int,
        pixelStride: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        fallback: Float
    ): Float {
        if (width <= 0 || height <= 0) return fallback
        val sampleX = x.roundToInt().coerceIn(0, width - 1)
        val sampleY = y.roundToInt().coerceIn(0, height - 1)
        val index = base + sampleY * rowStride + sampleX * pixelStride
        return if (index in 0 until buffer.limit()) {
            (buffer.get(index).toInt() and 0xFF).toFloat()
        } else {
            fallback
        }
    }

    private fun samplePlaneBilinear(
        buffer: ByteBuffer,
        base: Int,
        rowStride: Int,
        pixelStride: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        fallback: Float
    ): Float {
        if (width <= 0 || height <= 0) return fallback
        val clampedX = x.coerceIn(0f, (width - 1).toFloat())
        val clampedY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(clampedX).toInt()
        val y0 = floor(clampedY).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = clampedX - x0
        val ty = clampedY - y0

        fun valueAt(sampleX: Int, sampleY: Int): Float {
            val index = base + sampleY * rowStride + sampleX * pixelStride
            return if (index in 0 until buffer.limit()) {
                (buffer.get(index).toInt() and 0xFF).toFloat()
            } else {
                fallback
            }
        }

        val topLeft = valueAt(x0, y0)
        val topRight = valueAt(x1, y0)
        val bottomLeft = valueAt(x0, y1)
        val bottomRight = valueAt(x1, y1)
        val top = topLeft + (topRight - topLeft) * tx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * tx
        return top + (bottom - top) * ty
    }

    private fun normalizedRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private companion object {
        const val MODEL_ASSET = "models/movenet-thunder-fp16.tflite"
        const val RGB_CHANNELS = 3
        const val OUTPUT_VALUES_PER_KEYPOINT = 3
    }
}
