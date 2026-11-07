package com.topout.cam.sam

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.topout.cam.model.HoldMask
import com.topout.cam.model.TopOutConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MobileSAM + the same colour filtering/correction flow used by the iOS implementation.
 *
 * Model assets:
 *   app/src/main/assets/models/mobile_sam_encoder.onnx
 *   app/src/main/assets/models/mobile_sam_decoder.onnx
 */
class OnnxMobileSamSegmenter(
    context: Context
) : HoldSegmenter {
    private val appContext = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionMutex = Mutex()
    // 취소 불가능한 추론과 세션 종료를 같은 잠금 순서로 직렬화한다.
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closeRequested = AtomicBoolean(false)
    private val embeddingCacheGeneration = AtomicLong(0L)

    @Volatile private var encoderSession: OrtSession? = null
    @Volatile private var decoderSession: OrtSession? = null
    private var sessionOptions: OrtSession.SessionOptions? = null
    @Volatile private var closed = false
    @Volatile private var cachedEmbedding: CachedEmbedding? = null
    private var cachedZeroMaskInput: FloatBuffer? = null

    /** Loads the ONNX encoder/decoder sessions without starting the camera or running an image. */
    suspend fun prepare(): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            sessionMutex.withLock {
                ensureSessionsLocked()
                Unit
            }
        }
    }

    /**
     * Automatic mat segmentation used by TopOut's selection screen.
     *
     * A single cached image embedding is decoded at centre y=70%, 80%, 90%, and 95%. The
     * detector uses the prompt-connected component to anchor a robust upper-boundary line. It
     * keeps masks when non-outlier upper-edge columns support that line across at least 60% of the
     * visible width, the highest supporting pixel stays below the 50% image-height line, and the
     * boundary tilts no more than 15 degrees. Disconnected fragments may support the same line.
     * It then selects the highest remaining upper boundary.
     */
    suspend fun detectMatBoundary(bitmap: Bitmap): Result<List<Offset>> = withContext(Dispatchers.Default) {
        try {
            require(!bitmap.isRecycled) { "매트 경계를 찾을 카메라 이미지가 이미 해제되었습니다." }
            require(bitmap.width > 0 && bitmap.height > 0) { "매트 경계 이미지 크기가 올바르지 않습니다." }

            Result.success(sessionMutex.withLock {
                val sessions = ensureSessionsLocked()
                val embedding = obtainEmbedding(bitmap, sessions.encoder)
                val results = MAT_PROMPT_Y_VALUES.asSequence().map { promptY ->
                    // 시간 초과나 취소 뒤에는 현재 디코더 호출까지만 마치고 중단한다.
                    ensureActive()
                    MobileSamMatBoundaryDetector.PromptResult(
                        promptY = promptY,
                        masks = decodeMatMasks(
                            bitmap = bitmap,
                            positive = Offset(0.50f, promptY),
                            negative = Offset(0.50f, 0.28f),
                            embedding = embedding,
                            decoder = sessions.decoder
                        )
                    )
                }

                MobileSamMatBoundaryDetector.select(results)
                    ?: MobileSamMatBoundaryDetector.defaultLine
            })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * 메모리가 부족하거나 램 4GB 이하 기기에서 녹화를 시작할 때 모델 세션을 닫는다.
     * 진행 중인 추론이 끝난 뒤 닫고, 다음 분석에서 필요할 때 다시 연다.
     */
    fun releaseSessions() {
        if (closed) return
        embeddingCacheGeneration.incrementAndGet()
        cachedEmbedding = null
        teardownScope.launch {
            sessionMutex.withLock { if (!closed) closeSessionsLocked() }
        }
    }

    /** Releases the frozen-frame embedding once selection is complete. Model sessions stay warm. */
    fun releaseCachedEmbedding() {
        // 늦게 끝난 인코더 결과는 세대 번호로 폐기한다.
        embeddingCacheGeneration.incrementAndGet()
        cachedEmbedding = null
    }

    override suspend fun segment(
        bitmap: Bitmap,
        normalizedTap: Offset
    ): Result<HoldMask> = withContext(Dispatchers.Default) {
        try {
            require(!bitmap.isRecycled) { "선택할 카메라 이미지가 이미 해제되었습니다." }
            require(bitmap.width > 0 && bitmap.height > 0) { "선택할 이미지 크기가 올바르지 않습니다." }

            Result.success(sessionMutex.withLock {
                val sessions = ensureSessionsLocked()
                val embedding = obtainEmbedding(bitmap, sessions.encoder)
                runSelectionPipeline(
                    bitmap = bitmap,
                    normalizedTap = normalizedTap,
                    embedding = embedding,
                    decoder = sessions.decoder
                )
            })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /** 분석 제한 시간에 도달했을 때 탭 위치를 보존하는 기본 원형 마스크입니다. */
    internal fun fallbackHoldMask(bitmap: Bitmap, normalizedTap: Offset): HoldMask {
        require(!bitmap.isRecycled) { "선택할 카메라 이미지가 이미 해제되었습니다." }
        require(bitmap.width > 0 && bitmap.height > 0) { "선택할 이미지 크기가 올바르지 않습니다." }
        return makeFallbackHoldMask(bitmap, normalizedTap.clamped())
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        // 새 작업은 즉시 막고 진행 중인 호출이 잠금을 벗어난 뒤 세션을 닫는다.
        closed = true
        embeddingCacheGeneration.incrementAndGet()
        cachedEmbedding = null
        teardownScope.launch {
            sessionMutex.withLock { closeSessionsLocked() }
        }.invokeOnCompletion {
            teardownScope.cancel()
        }
    }

    private fun closeSessionsLocked() {
        cachedEmbedding = null
        cachedZeroMaskInput = null
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        runCatching { sessionOptions?.close() }
        encoderSession = null
        decoderSession = null
        sessionOptions = null
    }

    private fun ensureSessionsLocked(): Sessions {
        check(!closed) { "MobileSAM 실행기가 이미 종료되었습니다." }
        val existingEncoder = encoderSession
        val existingDecoder = decoderSession
        if (existingEncoder != null && existingDecoder != null) return Sessions(existingEncoder, existingDecoder)

        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // 인코더 추론 뒤 가장 컸던 작업 메모리를 앱이 켜져 있는 내내 붙잡지 않도록 arena를 끈다.
            setCPUArenaAllocator(false)
        }
        try {
            // 모델을 파일 경로로 읽어 대용량 임시 바이트 배열 생성을 피한다.
            val encoder = environment.createSession(materializeModelFile(ENCODER_FILE_NAME).absolutePath, options)
            encoderSession = encoder
            val decoder = environment.createSession(materializeModelFile(DECODER_FILE_NAME).absolutePath, options)
            decoderSession = decoder
            sessionOptions = options
            return Sessions(encoder, decoder)
        } catch (error: Throwable) {
            runCatching { encoderSession?.close() }
            runCatching { decoderSession?.close() }
            encoderSession = null
            decoderSession = null
            sessionOptions = null
            runCatching { options.close() }
            throw IllegalStateException(
                "MobileSAM 모델을 불러오지 못했습니다. " +
                    "app/src/main/assets/models/$ENCODER_FILE_NAME 및 " +
                    "app/src/main/assets/models/$DECODER_FILE_NAME 파일을 확인해주세요.",
                error
            )
        }
    }

    private fun materializeModelFile(fileName: String): File {
        val candidatePaths = listOf("models/$fileName", fileName)
        var selectedPath: String? = null
        var expectedLength: Long? = null
        var lastError: Throwable? = null

        for (path in candidatePaths) {
            try {
                appContext.assets.open(path).use { }
                selectedPath = path
                expectedLength = runCatching {
                    appContext.assets.openFd(path).use { descriptor -> descriptor.length }
                }
                    .getOrNull()
                    ?.takeIf { it > 0L }
                break
            } catch (error: Throwable) {
                lastError = error
            }
        }

        val assetPath = selectedPath ?: throw IllegalStateException(
            "assets/models/$fileName 파일을 찾지 못했습니다. ${lastError?.localizedMessage.orEmpty()}",
            lastError
        )
        val modelDirectory = File(appContext.noBackupFilesDir, "onnx-models").apply { mkdirs() }
        val target = File(modelDirectory, fileName)
        if (target.isFile && target.length() > 1_000_000L &&
            (expectedLength == null || target.length() == expectedLength)
        ) {
            return target
        }

        val temporary = File(modelDirectory, "$fileName.copying")
        temporary.delete()
        appContext.assets.open(assetPath).buffered().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        require(temporary.length() > 1_000_000L) {
            "MobileSAM 모델 복사 결과가 올바르지 않습니다: $fileName"
        }
        if (expectedLength != null) {
            require(temporary.length() == expectedLength) {
                "MobileSAM 모델 크기가 원본 asset과 다릅니다: $fileName"
            }
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("기존 MobileSAM 캐시 파일을 교체하지 못했습니다: $fileName")
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target
    }

    private suspend fun obtainEmbedding(bitmap: Bitmap, encoder: OrtSession): CachedEmbedding {
        currentCoroutineContext().ensureActive()
        val cacheGeneration = embeddingCacheGeneration.get()
        val cacheKey = BitmapCacheKey(
            identity = System.identityHashCode(bitmap),
            width = bitmap.width,
            height = bitmap.height,
            generationId = bitmap.generationId
        )
        cachedEmbedding?.takeIf { it.cacheKey == cacheKey }?.let { return it }

        val inputName = resolveName(encoder.inputInfo.keys, ENCODER_INPUT_ALIASES, "encoder 입력")
        val inputInfo = encoder.inputInfo.getValue(inputName).tensorInfo("encoder 입력")
        val preprocessing = preprocessBitmap(bitmap, inputInfo.shape)

        currentCoroutineContext().ensureActive()
        OnnxTensor.createTensor(environment, preprocessing.buffer, preprocessing.shape).use { inputTensor ->
            encoder.run(mapOf(inputName to inputTensor)).use { result ->
                currentCoroutineContext().ensureActive()
                val outputName = resolveName(
                    encoder.outputInfo.keys,
                    ENCODER_OUTPUT_ALIASES,
                    "encoder 출력"
                )
                val outputTensor = result.tensor(outputName, "encoder 출력")
                return CachedEmbedding(
                    cacheKey = cacheKey,
                    buffer = outputTensor.copyDirectFloatData(),
                    shape = outputTensor.info.shape.copyOf(),
                    transform = preprocessing.transform
                ).also { embedding ->
                    if (!closed && embeddingCacheGeneration.get() == cacheGeneration) {
                        cachedEmbedding = embedding
                    }
                }
            }
        }
    }

    // 탑홀드 선택: 한 번 디코딩한 다중 마스크에서 HoldOutlineExtractor가 홀드 하나를 고른다. iOS Pipeline.swift와 같다.

    private suspend fun runSelectionPipeline(
        bitmap: Bitmap,
        normalizedTap: Offset,
        embedding: CachedEmbedding,
        decoder: OrtSession
    ): HoldMask {
        currentCoroutineContext().ensureActive()
        val origin = normalizedTap.clamped()
        val decoded = decodeHoldCandidates(bitmap, origin, embedding, decoder)
        currentCoroutineContext().ensureActive()
        val outline = HoldOutlineExtractor().extract(
            HoldOutlineExtractor.Input(
                logits = decoded.logits,
                channelCount = decoded.channelCount,
                lowResSize = decoded.size,
                iouPredictions = decoded.iou,
                modelVisibleWidth = embedding.transform.resizedWidth.toFloat(),
                modelVisibleHeight = embedding.transform.resizedHeight.toFloat(),
                imageWidth = bitmap.width.toFloat(),
                imageHeight = bitmap.height.toFloat(),
                tapX = origin.x * bitmap.width,
                tapY = origin.y * bitmap.height,
                minimumDiameter = minimumHoldDiameter(bitmap),
                strokeWidth = max(TopOutConfig.MinRedLineWidth, min(bitmap.width, bitmap.height) * TopOutConfig.RedLineRatio)
            )
        )
        return outlineToHoldMask(outline, bitmap)
    }

    /** 외곽선을 원본 프레임 기준 정규화 좌표의 HoldMask로 바꾼다. */
    private fun outlineToHoldMask(outline: HoldOutline, bitmap: Bitmap): HoldMask {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val contour = when (outline) {
            is HoldOutline.Circle -> (0 until 64).map { index ->
                val angle = 2.0 * Math.PI * index / 64.0
                Offset(
                    (outline.centerX + cos(angle).toFloat() * outline.radius) / width,
                    (outline.centerY + sin(angle).toFloat() * outline.radius) / height
                )
            }
            is HoldOutline.Polygon -> (0 until outline.points.size / 2).map { index ->
                Offset(outline.points[index * 2] / width, outline.points[index * 2 + 1] / height)
            }
        }
        val left = contour.minOf { it.x }
        val top = contour.minOf { it.y }
        val right = contour.maxOf { it.x }
        val bottom = contour.maxOf { it.y }
        return HoldMask(
            center = Offset((left + right) / 2f, (top + bottom) / 2f),
            radiusX = max(0.002f, (right - left) / 2f),
            radiusY = max(0.002f, (bottom - top) / 2f),
            normalizedBounds = Rect(left, top, right, bottom),
            normalizedContour = contour
        )
    }

    /** 탑홀드 표시의 최소 지름. 짧은 변의 3.75%, 22px 이상, 짧은 변의 6% 이하. iOS minimumHoldDiameter와 같다. */
    private fun minimumHoldDiameter(bitmap: Bitmap): Float {
        val minSide = min(bitmap.width, bitmap.height).toFloat()
        val ideal = minSide * TopOutConfig.FallbackHoldMaskDiameterRatio
        return min(max(TopOutConfig.FallbackHoldMaskMinDiameter, ideal), minSide * TopOutConfig.FallbackHoldMaskMaxDiameterRatio)
    }

    // 디코더

    private fun decodeMatMasks(
        bitmap: Bitmap,
        positive: Offset,
        negative: Offset,
        embedding: CachedEmbedding,
        decoder: OrtSession
    ): List<MobileSamMatBoundaryDetector.SamBinaryMask> {
        val inputNames = decoder.inputInfo.keys
        val embeddingName = resolveName(inputNames, DECODER_EMBEDDING_ALIASES, "decoder embedding 입력")
        val pointCoordsName = resolveName(inputNames, POINT_COORDS_ALIASES, "point coordinates 입력")
        val pointLabelsName = resolveName(inputNames, POINT_LABELS_ALIASES, "point labels 입력")
        val maskInputName = resolveName(inputNames, MASK_INPUT_ALIASES, "mask 입력")
        val hasMaskInputName = resolveName(inputNames, HAS_MASK_INPUT_ALIASES, "has-mask 입력")
        val originalSizeName = resolveName(inputNames, ORIGINAL_SIZE_ALIASES, "원본 이미지 크기 입력")

        val coordsInfo = decoder.inputInfo.getValue(pointCoordsName).tensorInfo("point coordinates 입력")
        val coordsShape = pointCoordsShape(coordsInfo.shape)
        val coords = FloatArray(coordsShape.elementCountOrThrow("point coordinates 입력"))
        require(coords.size >= 4) { "MobileSAM decoder가 양성/음성 두 포인트 입력을 지원하지 않습니다." }
        coords[0] = positive.x.coerceIn(0f, 1f) * bitmap.width * embedding.transform.scaleX
        coords[1] = positive.y.coerceIn(0f, 1f) * bitmap.height * embedding.transform.scaleY
        coords[2] = negative.x.coerceIn(0f, 1f) * bitmap.width * embedding.transform.scaleX
        coords[3] = negative.y.coerceIn(0f, 1f) * bitmap.height * embedding.transform.scaleY

        val labelsInfo = decoder.inputInfo.getValue(pointLabelsName).tensorInfo("point labels 입력")
        val labelsShape = pointLabelsShape(labelsInfo.shape, max(2, coords.size / 2))
        val labels = FloatArray(labelsShape.elementCountOrThrow("point labels 입력")) { -1f }
        require(labels.size >= 2) { "MobileSAM decoder point labels 크기가 올바르지 않습니다." }
        labels[0] = 1f
        labels[1] = 0f

        val maskShape = positiveShape(
            decoder.inputInfo.getValue(maskInputName).tensorInfo("mask 입력").shape,
            longArrayOf(1, 1, TopOutConfig.MaskSize.toLong(), TopOutConfig.MaskSize.toLong())
        )
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            inputs[embeddingName] = tensor(embedding.buffer, embedding.shape)
            inputs[pointCoordsName] = tensor(coords, coordsShape)
            inputs[pointLabelsName] = tensor(labels, labelsShape)
            inputs[maskInputName] = tensor(zeroMaskInput(maskShape), maskShape)
            inputs[hasMaskInputName] = tensor(
                floatArrayOf(0f),
                positiveShape(
                    decoder.inputInfo.getValue(hasMaskInputName).tensorInfo("has-mask 입력").shape,
                    longArrayOf(1)
                )
            )
            inputs[originalSizeName] = tensor(
                floatArrayOf(bitmap.height.toFloat(), bitmap.width.toFloat()),
                positiveShape(
                    decoder.inputInfo.getValue(originalSizeName).tensorInfo("원본 이미지 크기 입력").shape,
                    longArrayOf(2)
                )
            )

            decoder.run(inputs).use { result ->
                val outputName = resolveMaskOutputName(decoder.outputInfo.keys)
                val tensor = result.tensor(outputName, "decoder mat mask 출력")
                val data = tensor.copyFloatData()
                val parsed = parseMaskShape(tensor.info.shape)
                val lowResolution = outputName in LOW_RES_MASK_ALIASES ||
                    tensor.info.shape.takeLast(2).all { it in 1L..512L }
                val visibleWidth = if (lowResolution) {
                    (embedding.transform.resizedWidth.toFloat() / embedding.transform.inputWidth * parsed.width)
                        .roundToInt().coerceIn(1, parsed.width)
                } else parsed.width
                val visibleHeight = if (lowResolution) {
                    (embedding.transform.resizedHeight.toFloat() / embedding.transform.inputHeight * parsed.height)
                        .roundToInt().coerceIn(1, parsed.height)
                } else parsed.height
                val planeSize = parsed.width * parsed.height

                return (0 until parsed.maskCount).mapNotNull { channel ->
                    val offset = channel * planeSize
                    if (offset + planeSize > data.size) return@mapNotNull null
                    var minimum = Float.POSITIVE_INFINITY
                    var maximum = Float.NEGATIVE_INFINITY
                    for (index in offset until offset + planeSize) {
                        minimum = min(minimum, data[index])
                        maximum = max(maximum, data[index])
                    }
                    val threshold = if (minimum >= 0f && maximum <= 1.0001f) 0.5f else 0f
                    val values = BooleanArray(planeSize)
                    for (y in 0 until visibleHeight) {
                        val row = y * parsed.width
                        for (x in 0 until visibleWidth) {
                            values[row + x] = data[offset + row + x] > threshold
                        }
                    }
                    MobileSamMatBoundaryDetector.SamBinaryMask(
                        width = parsed.width,
                        height = parsed.height,
                        visibleWidth = visibleWidth,
                        visibleHeight = visibleHeight,
                        positiveX = positive.x.coerceIn(0f, 1f),
                        values = values
                    )
                }
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    /** 한 점 프롬프트의 저해상도 다중 마스크 logit과 채널별 신뢰도. */
    private class HoldCandidates(val logits: FloatArray, val channelCount: Int, val size: Int, val iou: FloatArray)

    private fun decodeHoldCandidates(
        bitmap: Bitmap,
        normalizedPoint: Offset,
        embedding: CachedEmbedding,
        decoder: OrtSession
    ): HoldCandidates {
        val inputNames = decoder.inputInfo.keys
        val embeddingName = resolveName(inputNames, DECODER_EMBEDDING_ALIASES, "decoder embedding 입력")
        val pointCoordsName = resolveName(inputNames, POINT_COORDS_ALIASES, "point coordinates 입력")
        val pointLabelsName = resolveName(inputNames, POINT_LABELS_ALIASES, "point labels 입력")
        val maskInputName = resolveName(inputNames, MASK_INPUT_ALIASES, "mask 입력")
        val hasMaskInputName = resolveName(inputNames, HAS_MASK_INPUT_ALIASES, "has-mask 입력")
        val originalSizeName = resolveName(inputNames, ORIGINAL_SIZE_ALIASES, "원본 이미지 크기 입력")

        val coordsInfo = decoder.inputInfo.getValue(pointCoordsName).tensorInfo("point coordinates 입력")
        val coordsShape = pointCoordsShape(coordsInfo.shape)
        val coords = FloatArray(coordsShape.elementCountOrThrow("point coordinates 입력"))
        require(coords.size >= 2) { "point coordinates 입력 크기가 올바르지 않습니다." }
        coords[0] = normalizedPoint.x.coerceIn(0f, 1f) * bitmap.width * embedding.transform.scaleX
        coords[1] = normalizedPoint.y.coerceIn(0f, 1f) * bitmap.height * embedding.transform.scaleY

        val labelsInfo = decoder.inputInfo.getValue(pointLabelsName).tensorInfo("point labels 입력")
        val labelsShape = pointLabelsShape(labelsInfo.shape, max(1, coords.size / 2))
        // 나머지 칸은 SAM의 빈 점(-1)으로 둔다.
        val labels = FloatArray(labelsShape.elementCountOrThrow("point labels 입력")) { -1f }
        labels[0] = 1f

        val maskShape = positiveShape(
            decoder.inputInfo.getValue(maskInputName).tensorInfo("mask 입력").shape,
            longArrayOf(1, 1, TopOutConfig.MaskSize.toLong(), TopOutConfig.MaskSize.toLong())
        )
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            inputs[embeddingName] = tensor(embedding.buffer, embedding.shape)
            inputs[pointCoordsName] = tensor(coords, coordsShape)
            inputs[pointLabelsName] = tensor(labels, labelsShape)
            inputs[maskInputName] = tensor(zeroMaskInput(maskShape), maskShape)
            inputs[hasMaskInputName] = tensor(
                floatArrayOf(0f),
                positiveShape(
                    decoder.inputInfo.getValue(hasMaskInputName).tensorInfo("has-mask 입력").shape,
                    longArrayOf(1)
                )
            )
            inputs[originalSizeName] = tensor(
                floatArrayOf(bitmap.height.toFloat(), bitmap.width.toFloat()),
                positiveShape(
                    decoder.inputInfo.getValue(originalSizeName).tensorInfo("원본 이미지 크기 입력").shape,
                    longArrayOf(2)
                )
            )

            decoder.run(inputs).use { result ->
                val outputs = decoder.outputInfo.keys
                val maskName = resolveNameOrNull(outputs, LOW_RES_MASK_ALIASES)
                    ?: error("decoder 저해상도 mask 출력을 찾지 못했습니다. outputs=$outputs")
                val maskTensor = result.tensor(maskName, "decoder mask 출력")
                val parsed = parseMaskShape(maskTensor.info.shape)
                require(parsed.width == parsed.height) { "decoder mask가 정사각형이 아닙니다." }
                val logits = maskTensor.copyFloatData()
                require(logits.size >= parsed.maskCount * parsed.width * parsed.height) {
                    "decoder mask 출력 크기가 shape과 일치하지 않습니다."
                }
                val iou = resolveNameOrNull(outputs, IOU_ALIASES)
                    ?.let { result.tensor(it, "decoder iou 출력").copyFloatData() }
                    ?: FloatArray(0)
                return HoldCandidates(logits, parsed.maskCount, parsed.width, iou)
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun pointCoordsShape(raw: LongArray): LongArray = when (raw.size) {
        3 -> positiveShape(raw, longArrayOf(1, 2, 2))
        2 -> positiveShape(raw, longArrayOf(2, 2))
        else -> longArrayOf(1, 2, 2)
    }

    private fun pointLabelsShape(raw: LongArray, pointCount: Int): LongArray = when (raw.size) {
        2 -> positiveShape(raw, longArrayOf(1, pointCount.toLong()))
        1 -> positiveShape(raw, longArrayOf(pointCount.toLong()))
        else -> longArrayOf(1, pointCount.toLong())
    }

    /** 홀드를 찾지 못했을 때 탭 위치에 그리는 최소 크기 원. */
    private fun makeFallbackHoldMask(bitmap: Bitmap, point: Offset): HoldMask {
        val radius = minimumHoldDiameter(bitmap) / 2f
        val circle = HoldOutline.Circle(
            centerX = (point.x * bitmap.width).coerceIn(radius, max(radius, bitmap.width - radius)),
            centerY = (point.y * bitmap.height).coerceIn(radius, max(radius, bitmap.height - radius)),
            radius = radius
        )
        return outlineToHoldMask(circle, bitmap)
    }

    private fun preprocessBitmap(bitmap: Bitmap, rawShape: LongArray): PreprocessedImage {
        val shape = positiveShape(rawShape, longArrayOf(1, 3, TopOutConfig.SamSize.toLong(), TopOutConfig.SamSize.toLong()))
        require(shape.size == 4) { "MobileSAM encoder 입력은 4차원 텐서여야 합니다: ${shape.contentToString()}" }
        val channelsFirst = shape[1] == 3L || (shape[3] != 3L && shape[1] <= 4L)
        val targetHeight = if (channelsFirst) shape[2].toInt() else shape[1].toInt()
        val targetWidth = if (channelsFirst) shape[3].toInt() else shape[2].toInt()
        require(targetWidth > 0 && targetHeight > 0) { "encoder 입력 이미지 크기를 확인하지 못했습니다." }

        val scale = min(targetWidth / bitmap.width.toFloat(), targetHeight / bitmap.height.toFloat())
        val resizedWidth = max(1, (bitmap.width * scale).roundToInt()).coerceAtMost(targetWidth)
        val resizedHeight = max(1, (bitmap.height * scale).roundToInt()).coerceAtMost(targetHeight)
        val resized = if (bitmap.width == resizedWidth && bitmap.height == resizedHeight) bitmap
        else Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val pixels = IntArray(resizedWidth * resizedHeight)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)
        if (resized !== bitmap) resized.recycle()

        // 정규화 픽셀을 직접 입력 버퍼에 써 약 12MB의 임시 배열을 만들지 않는다.
        val values = ByteBuffer.allocateDirect(targetWidth * targetHeight * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val redMean = TopOutConfig.SamMean[0]
        val greenMean = TopOutConfig.SamMean[1]
        val blueMean = TopOutConfig.SamMean[2]
        val redStd = TopOutConfig.SamStd[0]
        val greenStd = TopOutConfig.SamStd[1]
        val blueStd = TopOutConfig.SamStd[2]
        val plane = targetWidth * targetHeight
        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {
                val pixel = pixels[y * resizedWidth + x]
                val red = (((pixel shr 16) and 0xFF) - redMean) / redStd
                val green = (((pixel shr 8) and 0xFF) - greenMean) / greenStd
                val blue = ((pixel and 0xFF) - blueMean) / blueStd
                if (channelsFirst) {
                    val index = y * targetWidth + x
                    values.put(index, red)
                    values.put(plane + index, green)
                    values.put(plane * 2 + index, blue)
                } else {
                    val index = (y * targetWidth + x) * 3
                    values.put(index, red)
                    values.put(index + 1, green)
                    values.put(index + 2, blue)
                }
            }
        }
        return PreprocessedImage(
            values.apply { rewind() },
            shape,
            ImageTransform(
                inputWidth = targetWidth,
                inputHeight = targetHeight,
                resizedWidth = resizedWidth,
                resizedHeight = resizedHeight,
                scaleX = resizedWidth / bitmap.width.toFloat(),
                scaleY = resizedHeight / bitmap.height.toFloat()
            )
        )
    }

    private fun resolveMaskOutputName(available: Set<String>): String =
        // iOS와 같이 저해상도 마스크 출력을 우선한다.
        resolveNameOrNull(available, LOW_RES_MASK_ALIASES)
            ?: resolveNameOrNull(available, FULL_MASK_ALIASES)
            ?: available.firstOrNull { it.contains("mask", ignoreCase = true) }
            ?: error("decoder mask 출력을 찾지 못했습니다. outputs=$available")

    private fun tensor(values: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, directFloatBuffer(values), shape)

    private fun tensor(values: FloatBuffer, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(
            environment,
            values.duplicate().apply { rewind() },
            shape
        )

    /** Reuses the decoder's immutable all-zero mask input across serialized MobileSAM calls. */
    private fun zeroMaskInput(shape: LongArray): FloatBuffer {
        val elementCount = shape.elementCountOrThrow("mask 입력")
        val cached = cachedZeroMaskInput
        if (cached != null && cached.capacity() == elementCount) return cached
        return ByteBuffer.allocateDirect(elementCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { cachedZeroMaskInput = it }
    }

    private fun resolveName(available: Set<String>, preferred: List<String>, description: String): String =
        resolveNameOrNull(available, preferred)
            ?: error("$description 이름을 찾지 못했습니다. available=$available")

    private fun resolveNameOrNull(available: Set<String>, preferred: List<String>): String? {
        preferred.firstOrNull { it in available }?.let { return it }
        preferred.firstNotNullOfOrNull { alias ->
            available.firstOrNull { it.equals(alias, ignoreCase = true) }
        }?.let { return it }
        return preferred.firstNotNullOfOrNull { alias ->
            available.firstOrNull { it.contains(alias, ignoreCase = true) }
        }
    }

    private fun NodeInfo.tensorInfo(description: String): TensorInfo =
        info as? TensorInfo ?: error("$description 텐서 메타데이터를 읽지 못했습니다.")

    private fun OrtSession.Result.tensor(name: String, description: String): OnnxTensor =
        get(name).orElseThrow { IllegalStateException("$description '$name'을 찾지 못했습니다.") } as? OnnxTensor
            ?: error("$description '$name'이 텐서가 아닙니다.")

    private fun OnnxTensor.copyFloatData(): FloatArray {
        val duplicate = requireNotNull(floatBuffer) {
            "ONNX 출력 텐서를 FloatBuffer로 변환하지 못했습니다. 모델 출력 타입이 float32인지 확인해주세요."
        }.duplicate()
        duplicate.rewind()
        return FloatArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun OnnxTensor.copyDirectFloatData(): FloatBuffer {
        val source = requireNotNull(floatBuffer) {
            "ONNX 출력 텐서를 FloatBuffer로 변환하지 못했습니다. 모델 출력 타입이 float32인지 확인해주세요."
        }.duplicate().apply { rewind() }
        return ByteBuffer.allocateDirect(source.remaining() * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(source)
                rewind()
            }
    }

    private fun positiveShape(raw: LongArray, fallback: LongArray): LongArray {
        if (raw.isEmpty()) return fallback.copyOf()
        return LongArray(raw.size) { index ->
            raw[index].takeIf { it > 0 } ?: fallback.getOrElse(index) { 1L }
        }
    }

    private fun LongArray.elementCountOrThrow(description: String): Int {
        var count = 1L
        forEach { dimension ->
            require(dimension > 0) { "$description shape이 올바르지 않습니다: ${contentToString()}" }
            count *= dimension
            require(count <= Int.MAX_VALUE) { "$description 텐서가 너무 큽니다." }
        }
        return count.toInt()
    }

    private fun parseMaskShape(shape: LongArray): ParsedMaskShape {
        val dimensions = shape.filter { it > 0 }.map { it.toInt() }
        require(dimensions.size >= 2) { "decoder mask shape이 올바르지 않습니다: ${shape.contentToString()}" }
        val width = dimensions.last()
        val height = dimensions[dimensions.lastIndex - 1]
        val leading = dimensions.dropLast(2)
        val maskCount = when {
            leading.isEmpty() -> 1
            leading.size == 1 -> leading[0]
            else -> leading.drop(1).fold(1) { total, value -> total * value }
        }.coerceAtLeast(1)
        return ParsedMaskShape(maskCount, width, height)
    }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                rewind()
            }

    private fun Offset.clamped(): Offset = Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    private data class Sessions(val encoder: OrtSession, val decoder: OrtSession)
    private data class BitmapCacheKey(val identity: Int, val width: Int, val height: Int, val generationId: Int)
    private data class CachedEmbedding(
        val cacheKey: BitmapCacheKey,
        val buffer: FloatBuffer,
        val shape: LongArray,
        val transform: ImageTransform
    )
    private data class ImageTransform(
        val inputWidth: Int,
        val inputHeight: Int,
        val resizedWidth: Int,
        val resizedHeight: Int,
        val scaleX: Float,
        val scaleY: Float
    )
    private data class PreprocessedImage(val buffer: FloatBuffer, val shape: LongArray, val transform: ImageTransform)
    private data class ParsedMaskShape(val maskCount: Int, val width: Int, val height: Int)

    companion object {
        const val ENCODER_FILE_NAME = "mobile_sam_encoder.onnx"
        const val DECODER_FILE_NAME = "mobile_sam_decoder.onnx"

        private val ENCODER_INPUT_ALIASES = listOf("input", "input_image", "images", "image")
        private val ENCODER_OUTPUT_ALIASES = listOf("image_embeddings", "embeddings", "output", "output_0")
        private val DECODER_EMBEDDING_ALIASES = listOf("image_embeddings", "embeddings")
        private val POINT_COORDS_ALIASES = listOf("point_coords", "point_coordinates", "coords")
        private val POINT_LABELS_ALIASES = listOf("point_labels", "labels")
        private val MASK_INPUT_ALIASES = listOf("mask_input", "mask_inputs")
        private val HAS_MASK_INPUT_ALIASES = listOf("has_mask_input", "has_mask")
        private val ORIGINAL_SIZE_ALIASES = listOf("orig_im_size", "original_image_size", "original_size")
        private val FULL_MASK_ALIASES = listOf("masks", "mask", "high_res_masks")
        private val LOW_RES_MASK_ALIASES = listOf("low_res_masks", "low_resolution_masks")
        private val IOU_ALIASES = listOf("iou_predictions", "scores")
        private val MAT_PROMPT_Y_VALUES = floatArrayOf(0.70f, 0.80f, 0.90f, 0.95f)
    }
}
