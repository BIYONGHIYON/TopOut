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
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    // ViewModel cancellation must not cancel teardown while a non-cancellable ORT call still owns
    // the mutex. This independent scope serializes session close without blocking the main thread.
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
     * detector keeps masks that span at least 40% of the visible width, whose highest pixel
     * stays below the 50% image-height line, and whose upper boundary tilts no more than 15 degrees.
     * It then selects the highest remaining upper boundary.
     */
    suspend fun detectMatBoundary(bitmap: Bitmap): Result<List<Offset>> = withContext(Dispatchers.Default) {
        runCatching {
            require(!bitmap.isRecycled) { "매트 경계를 찾을 카메라 이미지가 이미 해제되었습니다." }
            require(bitmap.width > 0 && bitmap.height > 0) { "매트 경계 이미지 크기가 올바르지 않습니다." }

            sessionMutex.withLock {
                val sessions = ensureSessionsLocked()
                val embedding = obtainEmbedding(bitmap, sessions.encoder)
                val results = MAT_PROMPT_Y_VALUES.asSequence().map { promptY ->
                    // A timeout/canceled selection can stop after the current native decoder call
                    // instead of running the remaining prompts in the background.
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
            }
        }
    }

    /** Releases the frozen-frame embedding once selection is complete. Model sessions stay warm. */
    fun releaseCachedEmbedding() {
        // A timed-out native encoder may still finish after this call. Its generation check keeps
        // that obsolete embedding from being published again during the recording stage.
        embeddingCacheGeneration.incrementAndGet()
        cachedEmbedding = null
    }

    override suspend fun warmUp(bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            require(!bitmap.isRecycled) { "선택할 카메라 이미지가 이미 해제되었습니다." }
            sessionMutex.withLock {
                val sessions = ensureSessionsLocked()
                obtainEmbedding(bitmap, sessions.encoder)
                Unit
            }
        }
    }

    override suspend fun segment(
        bitmap: Bitmap,
        normalizedTap: Offset,
        selectionZoom: Float
    ): Result<HoldMask> = withContext(Dispatchers.Default) {
        runCatching {
            require(!bitmap.isRecycled) { "선택할 카메라 이미지가 이미 해제되었습니다." }
            require(bitmap.width > 0 && bitmap.height > 0) { "선택할 이미지 크기가 올바르지 않습니다." }

            sessionMutex.withLock {
                val sessions = ensureSessionsLocked()
                val embedding = obtainEmbedding(bitmap, sessions.encoder)
                runSelectionPipeline(
                    bitmap = bitmap,
                    normalizedTap = normalizedTap,
                    selectionZoom = selectionZoom,
                    embedding = embedding,
                    decoder = sessions.decoder
                )
            }
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        // Reject newly queued work immediately. The current native call, if any, is allowed to
        // leave the mutex before its sessions are closed underneath it.
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
        }
        try {
            // Materialize uncompressed assets once and let ORT load from a file path. This avoids
            // retaining 40+ MB of temporary model ByteArrays during the launch thumbnail.
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
                expectedLength = runCatching { appContext.assets.openFd(path).length }
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

    private fun obtainEmbedding(bitmap: Bitmap, encoder: OrtSession): CachedEmbedding {
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

        OnnxTensor.createTensor(environment, preprocessing.buffer, preprocessing.shape).use { inputTensor ->
            encoder.run(mapOf(inputName to inputTensor)).use { result ->
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

    // MARK: iOS Pipeline.swift + Correction.swift

    private fun runSelectionPipeline(
        bitmap: Bitmap,
        normalizedTap: Offset,
        selectionZoom: Float,
        embedding: CachedEmbedding,
        decoder: OrtSession
    ): HoldMask {
        val origin = normalizedTap.clamped()
        val pixels = BitmapPixels(bitmap)
        val firstMask = decodeMask(bitmap, origin, embedding, decoder)
        val first = makePick(firstMask, origin, bitmap, pixels, TopOutConfig.HsvTolerance, origin)
        val chosen = chooseBestPick(
            first = first,
            firstMask = firstMask,
            bitmap = bitmap,
            pixels = pixels,
            embedding = embedding,
            decoder = decoder,
            origin = origin,
            selectionZoom = selectionZoom.coerceAtLeast(TopOutConfig.SelectionMinZoom)
        )

        return if (chosen.status == HitStatus.WALL || chosen.status == HitStatus.EMPTY || chosen.blob.isEmpty) {
            makeFallbackHoldMask(bitmap, origin)
        } else {
            componentToHoldMask(chosen.mask, chosen.blob, bitmap)
        }
    }

    private fun chooseBestPick(
        first: Pick,
        firstMask: BinaryMask,
        bitmap: Bitmap,
        pixels: BitmapPixels,
        embedding: CachedEmbedding,
        decoder: OrtSession,
        origin: Offset,
        selectionZoom: Float
    ): Pick {
        val reason = when (first.status) {
            HitStatus.GOOD -> return first
            HitStatus.WALL -> FixReason.WALL
            HitStatus.TINY, HitStatus.EMPTY -> FixReason.TINY
        }
        return correctedPick(
            first = first,
            firstMask = firstMask,
            reason = reason,
            bitmap = bitmap,
            pixels = pixels,
            embedding = embedding,
            decoder = decoder,
            origin = origin,
            selectionZoom = selectionZoom
        ) ?: first
    }

    private fun correctedPick(
        first: Pick,
        firstMask: BinaryMask,
        reason: FixReason,
        bitmap: Bitmap,
        pixels: BitmapPixels,
        embedding: CachedEmbedding,
        decoder: OrtSession,
        origin: Offset,
        selectionZoom: Float
    ): Pick? {
        if (reason == FixReason.TINY) {
            val samePoint = uniqueTolerances(TopOutConfig.LooseHsvTolerances.asList())
                .filter { abs(it - first.tolerance) > 0.0001f }
                .map { tolerance ->
                    makePick(firstMask, first.point, bitmap, pixels, tolerance, origin)
                }
                .filter { isUsable(it, first, reason) }
            bestCandidate(samePoint, reason, bitmap, selectionZoom)?.let { return it }
        }

        val tolerances = uniqueTolerances(
            if (reason == FixReason.WALL) {
                listOf(TopOutConfig.HsvTolerance)
            } else {
                listOf(TopOutConfig.HsvTolerance) + TopOutConfig.LooseHsvTolerances.asList()
            }
        )
        var decodeCount = 0
        for (ring in nearbyPointRings(origin, bitmap, selectionZoom)) {
            val candidates = mutableListOf<Pick>()
            for (point in ring) {
                if (decodeCount >= TopOutConfig.MaxCorrectionDecodes) break
                val mask = decodeMask(bitmap, point, embedding, decoder)
                decodeCount += 1
                for (tolerance in tolerances) {
                    val pick = makePick(mask, point, bitmap, pixels, tolerance, origin)
                    if (isUsable(pick, first, reason)) candidates += pick
                }
            }
            bestCandidate(candidates, reason, bitmap, selectionZoom)?.let { return it }
            if (decodeCount >= TopOutConfig.MaxCorrectionDecodes) break
        }
        return null
    }

    private fun isUsable(pick: Pick, first: Pick, reason: FixReason): Boolean {
        if (pick.status != HitStatus.GOOD) return false
        return when (reason) {
            FixReason.WALL -> true
            FixReason.TINY -> {
                val minimumArea = max(
                    first.blob.area * 2,
                    (pick.mask.width * pick.mask.height * TopOutConfig.TinyAreaRatio).toInt()
                )
                pick.blob.area >= minimumArea
            }
        }
    }

    private fun bestCandidate(
        candidates: List<Pick>,
        reason: FixReason,
        bitmap: Bitmap,
        selectionZoom: Float
    ): Pick? {
        if (candidates.isEmpty()) return null
        val maxDistance = searchRadiusPixels(
            TopOutConfig.SearchRadii.lastOrNull() ?: 0.03f,
            bitmap,
            selectionZoom
        )
        return candidates.minByOrNull { pick ->
            val distanceScore = if (maxDistance > 0f) pick.distancePixels / maxDistance else 0f
            val areaScore = sqrt(pick.blob.area / max(1f, (pick.mask.width * pick.mask.height).toFloat()))
            when (reason) {
                FixReason.WALL -> distanceScore * 0.85f - areaScore * 0.15f
                FixReason.TINY -> distanceScore * 0.55f - areaScore * 0.45f
            }
        }
    }

    private fun nearbyPointRings(origin: Offset, bitmap: Bitmap, selectionZoom: Float): List<List<Offset>> {
        val directions = arrayOf(
            1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f,
            0.707f to 0.707f, -0.707f to 0.707f,
            0.707f to -0.707f, -0.707f to -0.707f
        )
        val seen = mutableSetOf<String>()
        return TopOutConfig.SearchRadii.mapNotNull { ratio ->
            val radius = searchRadiusPixels(ratio, bitmap, selectionZoom)
            val ring = directions.mapNotNull { (dx, dy) ->
                val xPixels = origin.x * bitmap.width + dx * radius
                val yPixels = origin.y * bitmap.height + dy * radius
                if (xPixels !in 0f..bitmap.width.toFloat() || yPixels !in 0f..bitmap.height.toFloat()) {
                    null
                } else {
                    val key = "${xPixels.roundToInt()}_${yPixels.roundToInt()}"
                    if (!seen.add(key)) null
                    else Offset(xPixels / bitmap.width, yPixels / bitmap.height).clamped()
                }
            }
            ring.takeIf { it.isNotEmpty() }
        }
    }

    private fun searchRadiusPixels(ratio: Float, bitmap: Bitmap, selectionZoom: Float): Float =
        min(bitmap.width, bitmap.height) * ratio / max(selectionZoom, TopOutConfig.MinZoom)

    private fun uniqueTolerances(values: List<Float>): List<Float> {
        val result = mutableListOf<Float>()
        values.forEach { value ->
            if (result.none { abs(it - value) < 0.0001f }) result += value
        }
        return result
    }

    // MARK: Decoder

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

    private fun decodeMask(
        bitmap: Bitmap,
        normalizedPoint: Offset,
        embedding: CachedEmbedding,
        decoder: OrtSession
    ): BinaryMask {
        val inputNames = decoder.inputInfo.keys
        val embeddingName = resolveName(inputNames, DECODER_EMBEDDING_ALIASES, "decoder embedding 입력")
        val pointCoordsName = resolveName(inputNames, POINT_COORDS_ALIASES, "point coordinates 입력")
        val pointLabelsName = resolveName(inputNames, POINT_LABELS_ALIASES, "point labels 입력")
        val maskInputName = resolveName(inputNames, MASK_INPUT_ALIASES, "mask 입력")
        val hasMaskInputName = resolveName(inputNames, HAS_MASK_INPUT_ALIASES, "has-mask 입력")
        val originalSizeName = resolveName(inputNames, ORIGINAL_SIZE_ALIASES, "원본 이미지 크기 입력")

        val pointX = normalizedPoint.x.coerceIn(0f, 1f) * bitmap.width
        val pointY = normalizedPoint.y.coerceIn(0f, 1f) * bitmap.height
        val transformedX = pointX * embedding.transform.scaleX
        val transformedY = pointY * embedding.transform.scaleY

        val coordsInfo = decoder.inputInfo.getValue(pointCoordsName).tensorInfo("point coordinates 입력")
        val coordsShape = pointCoordsShape(coordsInfo.shape)
        val coords = FloatArray(coordsShape.elementCountOrThrow("point coordinates 입력"))
        require(coords.size >= 2) { "point coordinates 입력 크기가 올바르지 않습니다." }
        coords[0] = transformedX
        coords[1] = transformedY

        val labelsInfo = decoder.inputInfo.getValue(pointLabelsName).tensorInfo("point labels 입력")
        val labelsShape = pointLabelsShape(labelsInfo.shape, max(1, coords.size / 2))
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
                val outputName = resolveMaskOutputName(decoder.outputInfo.keys)
                val tensor = result.tensor(outputName, "decoder mask 출력")
                return selectMask(
                    data = tensor.copyFloatData(),
                    shape = tensor.info.shape,
                    originalWidth = bitmap.width,
                    originalHeight = bitmap.height,
                    transform = embedding.transform,
                    isLowResolution = outputName in LOW_RES_MASK_ALIASES ||
                        tensor.info.shape.takeLast(2).all { it in 1L..512L }
                )
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

    private fun selectMask(
        data: FloatArray,
        shape: LongArray,
        originalWidth: Int,
        originalHeight: Int,
        transform: ImageTransform,
        isLowResolution: Boolean
    ): BinaryMask {
        val parsed = parseMaskShape(shape)
        // Swift maskChannel(_:) always selects channel 1 when multiple masks exist.
        val maskIndex = if (parsed.maskCount > 1) 1 else 0
        val maskOffset = maskIndex * parsed.width * parsed.height
        require(maskOffset + parsed.width * parsed.height <= data.size) {
            "decoder mask 출력 크기가 shape과 일치하지 않습니다."
        }

        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        for (index in maskOffset until maskOffset + parsed.width * parsed.height) {
            minimum = min(minimum, data[index])
            maximum = max(maximum, data[index])
        }
        val threshold = if (minimum >= 0f && maximum <= 1.0001f) 0.5f else 0f

        val downScale = min(1f, TopOutConfig.MaskSize.toFloat() / max(originalWidth, originalHeight).toFloat())
        val outputWidth = max(1, (originalWidth * downScale).roundToInt())
        val outputHeight = max(1, (originalHeight * downScale).roundToInt())
        val binary = BooleanArray(outputWidth * outputHeight)

        for (y in 0 until outputHeight) {
            val originalY = (y + 0.5f) / outputHeight * originalHeight
            val sourceY = if (isLowResolution) {
                (originalY * transform.scaleY / transform.inputHeight * parsed.height).toInt()
            } else {
                (originalY * transform.scaleY / transform.inputHeight * parsed.height).toInt()
            }.coerceIn(0, parsed.height - 1)
            for (x in 0 until outputWidth) {
                val originalX = (x + 0.5f) / outputWidth * originalWidth
                val sourceX = if (isLowResolution) {
                    (originalX * transform.scaleX / transform.inputWidth * parsed.width).toInt()
                } else {
                    (originalX * transform.scaleX / transform.inputWidth * parsed.width).toInt()
                }.coerceIn(0, parsed.width - 1)
                binary[y * outputWidth + x] = data[maskOffset + sourceY * parsed.width + sourceX] > threshold
            }
        }
        return BinaryMask(outputWidth, outputHeight, binary)
    }

    // MARK: Mask.swift colour filter/blob extraction

    private fun makePick(
        mask: BinaryMask,
        point: Offset,
        bitmap: Bitmap,
        pixels: BitmapPixels,
        tolerance: Float,
        origin: Offset
    ): Pick {
        val target = pixels.averageRgb(point, TopOutConfig.ColorSampleRadius)
        val targetHsv = rgbToHsv(target)
        val valid = BooleanArray(mask.values.size)
        for (y in 0 until mask.height) {
            val imageY = ((y + 0.5f) / mask.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            for (x in 0 until mask.width) {
                val index = y * mask.width + x
                if (!mask.values[index]) continue
                val imageX = ((x + 0.5f) / mask.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val hsv = rgbToHsv(pixels.rgb(imageX, imageY))
                if (hsvDistance(targetHsv, hsv) <= tolerance) valid[index] = true
            }
        }
        val blob = largestBlob(valid, mask.width, mask.height)
        val status = classify(blob, mask)
        val distance = hypot(
            ((point.x - origin.x) * bitmap.width).toDouble(),
            ((point.y - origin.y) * bitmap.height).toDouble()
        ).toFloat()
        return Pick(point, tolerance, mask, blob, status, distance)
    }

    private fun largestBlob(valid: BooleanArray, width: Int, height: Int): Blob {
        val visited = BooleanArray(valid.size)
        var best = IntArray(0)
        val queue = ArrayDeque<Int>()
        for (start in valid.indices) {
            if (!valid[start] || visited[start]) continue
            val builder = IntArrayBuilder()
            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                builder.add(current)
                val x = current % width
                val y = current / width
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = x + dx
                        val nextY = y + dy
                        if (nextX !in 0 until width || nextY !in 0 until height) continue
                        val next = nextY * width + nextX
                        if (valid[next] && !visited[next]) {
                            visited[next] = true
                            queue.add(next)
                        }
                    }
                }
            }
            val component = builder.toArray()
            if (component.size > best.size) best = component
        }
        return makeBlob(best, width)
    }

    private fun makeBlob(indices: IntArray, width: Int): Blob {
        if (indices.isEmpty()) return Blob.Empty
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        indices.forEach { index ->
            val x = index % width
            val y = index / width
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        return Blob(indices, IntBounds(minX, minY, maxX, maxY))
    }

    private fun classify(blob: Blob, mask: BinaryMask): HitStatus {
        if (blob.isEmpty) return HitStatus.EMPTY
        val spanX = blob.bounds.width / max(1f, mask.width.toFloat())
        val spanY = blob.bounds.height / max(1f, mask.height.toFloat())
        val areaRatio = blob.area / max(1f, (mask.width * mask.height).toFloat())
        return when {
            spanX >= TopOutConfig.WallSpanRatio || spanY >= TopOutConfig.WallSpanRatio -> HitStatus.WALL
            areaRatio <= TopOutConfig.TinyAreaRatio -> HitStatus.TINY
            else -> HitStatus.GOOD
        }
    }

    private fun componentToHoldMask(mask: BinaryMask, blob: Blob, bitmap: Bitmap): HoldMask {
        val bounds = Rect(
            blob.bounds.minX.toFloat() / mask.width,
            blob.bounds.minY.toFloat() / mask.height,
            (blob.bounds.maxX + 1f) / mask.width,
            (blob.bounds.maxY + 1f) / mask.height
        )
        var sumX = 0.0
        var sumY = 0.0
        blob.indices.forEach { index ->
            sumX += index % mask.width
            sumY += index / mask.width
        }
        val center = Offset(
            ((sumX / blob.area + 0.5) / mask.width).toFloat(),
            ((sumY / blob.area + 0.5) / mask.height).toFloat()
        )
        val roundedBounds = roundedDrawingBounds(bounds, bitmap)
        val contour = if (shouldUseRoundedShape(blob, roundedBounds)) {
            roundedRectContour(roundedBounds, TopOutConfig.RoundedCornerRatio)
        } else {
            contourPoints(blob, mask).takeIf { it.size >= TopOutConfig.ContourMinPoints }
                ?: roundedRectContour(bounds, 0.12f)
        }
        return HoldMask(
            center = center,
            radiusX = max(0.002f, bounds.width / 2f),
            radiusY = max(0.002f, bounds.height / 2f),
            normalizedBounds = bounds,
            normalizedContour = contour
        )
    }

    private fun shouldUseRoundedShape(blob: Blob, roundedBounds: Rect): Boolean {
        val maxSpan = max(blob.bounds.width, blob.bounds.height).toFloat()
        val minSpan = max(1, min(blob.bounds.width, blob.bounds.height)).toFloat()
        if (maxSpan / minSpan >= TopOutConfig.RoundAspectLimit) return false
        val areaRatio = roundedBounds.width * roundedBounds.height
        if (roundedBounds.width >= TopOutConfig.WallSpanRatio ||
            roundedBounds.height >= TopOutConfig.WallSpanRatio ||
            areaRatio >= TopOutConfig.RoundedMaxAreaRatio
        ) return false
        return maxSpan <= TopOutConfig.TinyRoundMaxSpan || blob.area <= TopOutConfig.TinyRoundMaxArea
    }

    /** Mask.swift pads only a rounded outline; the top-hold hit box remains the raw blob bounds. */
    private fun roundedDrawingBounds(bounds: Rect, bitmap: Bitmap): Rect {
        val minSide = min(bitmap.width, bitmap.height).toFloat()
        val redWidth = max(minSide * TopOutConfig.RedLineRatio, TopOutConfig.MinRedLineWidth)
        val padPixels = redWidth * TopOutConfig.RoundedPaddingRatio
        val padX = padPixels / max(1f, bitmap.width.toFloat())
        val padY = padPixels / max(1f, bitmap.height.toFloat())
        return Rect(
            left = (bounds.left - padX).coerceIn(0f, 1f),
            top = (bounds.top - padY).coerceIn(0f, 1f),
            right = (bounds.right + padX).coerceIn(0f, 1f),
            bottom = (bounds.bottom + padY).coerceIn(0f, 1f)
        )
    }

    private fun contourPoints(blob: Blob, mask: BinaryMask): List<Offset> {
        val inside = BooleanArray(mask.values.size)
        blob.indices.forEach { if (it in inside.indices) inside[it] = true }
        val edge = blob.indices.filter { index ->
            val x = index % mask.width
            val y = index / mask.width
            var boundary = false
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until mask.width || ny !in 0 until mask.height || !inside[ny * mask.width + nx]) {
                        boundary = true
                        break
                    }
                }
                if (boundary) break
            }
            boundary
        }
        if (edge.isEmpty()) return emptyList()
        var raw = edge.map { index ->
            Offset(
                ((index % mask.width) + 0.5f) / mask.width,
                ((index / mask.width) + 0.5f) / mask.height
            )
        }
        raw = decimate(raw, TopOutConfig.ContourRawPointLimit)
        var ordered = convexHull(raw)
        ordered = decimate(ordered, TopOutConfig.ContourPointLimit)
        repeat(TopOutConfig.ContourSmoothIterations) { ordered = chaikinSmooth(ordered) }
        return ordered
    }

    private fun decimate(points: List<Offset>, maximum: Int): List<Offset> {
        if (maximum <= 0 || points.size <= maximum) return points
        val step = max(1, ceil(points.size.toDouble() / maximum).toInt())
        return points.indices.step(step).map { points[it] }
    }

    private fun convexHull(points: List<Offset>): List<Offset> {
        if (points.size <= 3) return points
        val sorted = points.sortedWith(compareBy<Offset> { it.x }.thenBy { it.y })
        fun cross(origin: Offset, a: Offset, b: Offset): Float =
            (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
        val lower = mutableListOf<Offset>()
        sorted.forEach { point ->
            while (lower.size >= 2 && cross(lower[lower.lastIndex - 1], lower.last(), point) <= 0f) {
                lower.removeAt(lower.lastIndex)
            }
            lower += point
        }
        val upper = mutableListOf<Offset>()
        sorted.asReversed().forEach { point ->
            while (upper.size >= 2 && cross(upper[upper.lastIndex - 1], upper.last(), point) <= 0f) {
                upper.removeAt(upper.lastIndex)
            }
            upper += point
        }
        if (lower.isNotEmpty()) lower.removeAt(lower.lastIndex)
        if (upper.isNotEmpty()) upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun chaikinSmooth(points: List<Offset>): List<Offset> {
        if (points.size < 4) return points
        val output = ArrayList<Offset>(points.size * 2)
        points.indices.forEach { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            output += Offset(a.x * 0.75f + b.x * 0.25f, a.y * 0.75f + b.y * 0.25f)
            output += Offset(a.x * 0.25f + b.x * 0.75f, a.y * 0.25f + b.y * 0.75f)
        }
        return output
    }

    private fun roundedRectContour(bounds: Rect, radiusRatio: Float): List<Offset> {
        val radius = min(bounds.width, bounds.height) * radiusRatio.coerceIn(0f, 0.5f)
        if (radius <= 0f) return listOf(
            Offset(bounds.left, bounds.top), Offset(bounds.right, bounds.top),
            Offset(bounds.right, bounds.bottom), Offset(bounds.left, bounds.bottom)
        )
        val centers = listOf(
            Offset(bounds.right - radius, bounds.top + radius) to (-90f to 0f),
            Offset(bounds.right - radius, bounds.bottom - radius) to (0f to 90f),
            Offset(bounds.left + radius, bounds.bottom - radius) to (90f to 180f),
            Offset(bounds.left + radius, bounds.top + radius) to (180f to 270f)
        )
        val result = mutableListOf<Offset>()
        centers.forEach { (center, range) ->
            for (step in 0..7) {
                val degrees = range.first + (range.second - range.first) * step / 7f
                val radians = Math.toRadians(degrees.toDouble())
                result += Offset(
                    center.x + cos(radians).toFloat() * radius,
                    center.y + sin(radians).toFloat() * radius
                )
            }
        }
        return result
    }

    private fun makeFallbackHoldMask(bitmap: Bitmap, point: Offset): HoldMask {
        val minSide = min(bitmap.width, bitmap.height).toFloat()
        val ideal = minSide * TopOutConfig.FallbackHoldMaskDiameterRatio
        val maximum = minSide * TopOutConfig.FallbackHoldMaskMaxDiameterRatio
        val diameter = min(max(TopOutConfig.FallbackHoldMaskMinDiameter, ideal), maximum)
        val half = diameter / 2f
        val centerX = (point.x * bitmap.width).coerceIn(half, bitmap.width - half)
        val centerY = (point.y * bitmap.height).coerceIn(half, bitmap.height - half)
        val bounds = Rect(
            (centerX - half) / bitmap.width,
            (centerY - half) / bitmap.height,
            (centerX + half) / bitmap.width,
            (centerY + half) / bitmap.height
        )
        val contour = (0 until 48).map { index ->
            val angle = 2.0 * Math.PI * index / 48.0
            Offset(
                centerX / bitmap.width + cos(angle).toFloat() * half / bitmap.width,
                centerY / bitmap.height + sin(angle).toFloat() * half / bitmap.height
            )
        }
        return HoldMask(
            center = Offset(centerX / bitmap.width, centerY / bitmap.height),
            radiusX = half / bitmap.width,
            radiusY = half / bitmap.height,
            normalizedBounds = bounds,
            normalizedContour = contour
        )
    }

    private fun rgbToHsv(rgb: Rgb): Hsv {
        val r = rgb.r / 255f
        val g = rgb.g / 255f
        val b = rgb.b / 255f
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val delta = maximum - minimum
        var hue = 0f
        if (delta != 0f) {
            hue = when (maximum) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
        }
        if (hue < 0f) hue += 360f
        return Hsv(hue, if (maximum == 0f) 0f else delta / maximum, maximum)
    }

    private fun hsvDistance(a: Hsv, b: Hsv): Float {
        var hue = abs(a.h - b.h)
        if (hue > 180f) hue = 360f - hue
        hue /= 180f
        return hue * 0.8f + abs(a.s - b.s) * 0.1f + abs(a.v - b.v) * 0.1f
    }

    // MARK: Encoder preprocessing / metadata helpers

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

        // Write normalized pixels straight into ONNX's direct input buffer. Keeping an additional
        // 1024x1024x3 FloatArray here would add roughly 12 MB to MobileSAM's peak heap usage.
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
        // Swift explicitly consumes low_res_masks.
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
    private data class BinaryMask(val width: Int, val height: Int, val values: BooleanArray)
    private data class IntBounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }
    private data class Blob(val indices: IntArray, val bounds: IntBounds) {
        val area: Int get() = indices.size
        val isEmpty: Boolean get() = indices.isEmpty()
        companion object {
            val Empty = Blob(IntArray(0), IntBounds(0, 0, -1, -1))
        }
    }
    private data class Pick(
        val point: Offset,
        val tolerance: Float,
        val mask: BinaryMask,
        val blob: Blob,
        val status: HitStatus,
        val distancePixels: Float
    )
    private enum class HitStatus { GOOD, WALL, TINY, EMPTY }
    private enum class FixReason { WALL, TINY }
    private data class Rgb(val r: Int, val g: Int, val b: Int)
    private data class Hsv(val h: Float, val s: Float, val v: Float)

    private class BitmapPixels(private val bitmap: Bitmap) {
        private val values = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        fun rgb(x: Int, y: Int): Rgb {
            val pixel = values[y.coerceIn(0, bitmap.height - 1) * bitmap.width + x.coerceIn(0, bitmap.width - 1)]
            return Rgb((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
        }
        fun averageRgb(point: Offset, radius: Int): Rgb {
            val centerX = (point.x * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
            val centerY = (point.y * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0
            for (y in max(0, centerY - radius)..min(bitmap.height - 1, centerY + radius)) {
                for (x in max(0, centerX - radius)..min(bitmap.width - 1, centerX + radius)) {
                    val color = rgb(x, y)
                    red += color.r
                    green += color.g
                    blue += color.b
                    count += 1
                }
            }
            return Rgb((red / max(1, count)).toInt(), (green / max(1, count)).toInt(), (blue / max(1, count)).toInt())
        }
    }

    private class IntArrayBuilder(initialCapacity: Int = 256) {
        private var storage = IntArray(initialCapacity)
        private var count = 0
        fun add(value: Int) {
            if (count == storage.size) storage = storage.copyOf(storage.size * 2)
            storage[count++] = value
        }
        fun toArray(): IntArray = storage.copyOf(count)
    }

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
        private val MAT_PROMPT_Y_VALUES = floatArrayOf(0.70f, 0.80f, 0.90f, 0.95f)
    }
}
