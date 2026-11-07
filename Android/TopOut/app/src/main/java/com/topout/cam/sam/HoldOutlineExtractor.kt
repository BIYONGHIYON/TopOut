package com.topout.cam.sam

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 결과 화면에서 탭한 탑홀드의 최종 표시 모양. 좌표는 원본 프레임 픽셀 단위다. iOS HoldOutline과 같다. */
sealed class HoldOutline {
    /** 홀드가 최소 표시 크기보다 작거나 홀드를 찾지 못하면 최소 크기 원으로 표시한다. */
    data class Circle(val centerX: Float, val centerY: Float, val radius: Float) : HoldOutline()

    /** 홀드 가장자리를 따라 바깥에 그리는 닫힌 외곽선. 좌표는 x, y가 번갈아 들어 있다. */
    class Polygon(val points: FloatArray) : HoldOutline()
}

/**
 * MobileSAM 후보 마스크 중 탭한 홀드 하나를 고르고 깔끔한 외곽선으로 정리한다.
 * iOS HoldOutline.swift와 같은 순서·같은 값을 쓴다.
 *
 * 1. 후보: 한 점 프롬프트의 다중 마스크(채널 1–3)를 모두 본다.
 * 2. 격자: 저해상도 logit을 긴 변 512칸 격자로 이중선형 확대해 계단 현상을 없앤다.
 * 3. 영역: 탭한 칸과 이어진 영역만 남기고 안쪽 구멍을 메운다. 색상으로 자르지 않는다.
 * 4. 거름: 화면의 25% 이상 펼쳐지거나 3.5% 이상 덮으면 벽·매트로 보고 버린다.
 * 5. 선택: 모델 신뢰도(IoU) + 0.5 × 안정도. 점수 차이가 0.03 이내면 더 작은 마스크(탭한 홀드 하나)를 고른다.
 * 6. 정리: 열림으로 잔가지를 떼고, 선 굵기 절반만큼 바깥으로 키운 뒤 닫힘으로 홈을 메운다.
 * 7. 모양: 최소 원 안에 들어가면 원, 아니면 윤곽선 → Douglas–Peucker → Chaikin.
 */
class HoldOutlineExtractor {
    class Input(
        /** 채널 × size × size 저해상도 logit (행 우선). */
        val logits: FloatArray,
        val channelCount: Int,
        val lowResSize: Int,
        /** 채널별 모델 신뢰도. 없으면 빈 배열. */
        val iouPredictions: FloatArray,
        /** 1024 입력 안에서 실제 이미지가 차지한 크기. */
        val modelVisibleWidth: Float,
        val modelVisibleHeight: Float,
        val imageWidth: Float,
        val imageHeight: Float,
        val tapX: Float,
        val tapY: Float,
        /** 최소 표시 원 지름과 빨간 테두리 굵기(원본 픽셀). */
        val minimumDiameter: Float,
        val strokeWidth: Float
    )

    object Tuning {
        const val GridLongSide = 512
        const val SeedSearchRatio = 0.02f
        const val MaxSpanRatio = 0.25f
        const val MaxAreaRatio = 0.035f
        const val StabilityWeight = 0.5f
        const val StabilityOffset = 1f
        const val PreferSmallerMargin = 0.03f
        const val PreferSmallerAreaRatio = 1.6f
        const val OpenRadius = 2f
        const val CloseRadius = 3f
        const val OutlinePadStrokeRatio = 0.5f
        const val SimplifyTolerance = 1f
        const val SmoothIterations = 3
    }

    private class Grid(val width: Int, val height: Int, val values: BooleanArray = BooleanArray(width * height)) {
        operator fun get(x: Int, y: Int): Boolean = values[y * width + x]
        val count: Int get() = values.count { it }
    }

    private class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    private class Candidate(val region: Grid, val score: Float, val areaRatio: Float)

    fun extract(input: Input): HoldOutline {
        val fallback = circle(input.tapX, input.tapY, input.minimumDiameter / 2f, input)
        val modelLong = max(input.modelVisibleWidth, input.modelVisibleHeight)
        if (modelLong <= 0f || input.imageWidth <= 0f || input.imageHeight <= 0f || input.channelCount <= 0) {
            return fallback
        }

        // 격자 1칸 = 모델 입력 (1 / gridScale) px.
        val gridScale = Tuning.GridLongSide / modelLong
        val gridWidth = max(1, (input.modelVisibleWidth * gridScale).roundToInt())
        val gridHeight = max(1, (input.modelVisibleHeight * gridScale).roundToInt())
        val tapX = (input.tapX * gridWidth / input.imageWidth).toInt().coerceIn(0, gridWidth - 1)
        val tapY = (input.tapY * gridHeight / input.imageHeight).toInt().coerceIn(0, gridHeight - 1)
        val seedSearch = Tuning.SeedSearchRatio * min(gridWidth, gridHeight)

        val channels = if (input.channelCount >= 4) 1 until 4 else 0 until input.channelCount
        val candidates = mutableListOf<Candidate>()
        for (channel in channels) {
            val field = sampledField(input, channel, gridWidth, gridHeight, gridScale)
            val positive = Grid(gridWidth, gridHeight)
            for (index in field.indices) if (field[index] > 0f) positive.values[index] = true
            val component = component(positive, tapX, tapY, seedSearch) ?: continue
            val region = filledHoles(component)
            val box = bounds(region) ?: continue

            val spanX = (box.maxX - box.minX + 1).toFloat() / gridWidth
            val spanY = (box.maxY - box.minY + 1).toFloat() / gridHeight
            val areaRatio = region.count.toFloat() / (gridWidth * gridHeight)
            // 벽·매트·천장처럼 넓게 퍼진 마스크는 홀드가 아니다.
            if (spanX >= Tuning.MaxSpanRatio || spanY >= Tuning.MaxSpanRatio || areaRatio >= Tuning.MaxAreaRatio) continue

            val iou = input.iouPredictions.getOrElse(channel) { 0f }
            val stability = stabilityScore(field, box, gridWidth, gridHeight)
            candidates += Candidate(region, iou + Tuning.StabilityWeight * stability, areaRatio)
        }
        val chosen = choose(candidates) ?: return fallback

        var region = opened(chosen.region, Tuning.OpenRadius, tapX, tapY, seedSearch)
        val box = bounds(region) ?: return fallback

        val gridToImageX = input.imageWidth / gridWidth
        val gridToImageY = input.imageHeight / gridHeight
        val pad = input.strokeWidth * Tuning.OutlinePadStrokeRatio
        val holdWidth = (box.maxX - box.minX + 1) * gridToImageX
        val holdHeight = (box.maxY - box.minY + 1) * gridToImageY
        if (max(holdWidth, holdHeight) + pad * 2f <= input.minimumDiameter) {
            // 작은 홀드는 최소 크기 원을 홀드 가운데에 둔다.
            val (centerX, centerY) = centroid(region, gridToImageX, gridToImageY)
            return circle(centerX, centerY, input.minimumDiameter / 2f, input)
        }

        // 테두리가 홀드를 가리지 않게 선 굵기 절반만큼 바깥으로 키우고, 닫힘으로 오목한 톱니를 메운다.
        val padCells = pad / max(gridToImageX, gridToImageY)
        region = filledHoles(dilated(region, padCells + Tuning.CloseRadius))
        region = eroded(region, Tuning.CloseRadius)
        region = component(region, tapX, tapY, Float.MAX_VALUE) ?: region

        val boundary = traceBoundary(region)
        if (boundary.size < 8) return fallback
        val simplified = simplifyClosed(boundary, Tuning.SimplifyTolerance)
        val smoothed = chaikin(simplified, Tuning.SmoothIterations)
        if (smoothed.size < 8) return fallback
        val points = FloatArray(smoothed.size)
        for (index in 0 until smoothed.size / 2) {
            points[index * 2] = (smoothed[index * 2] + 0.5f) * gridToImageX
            points[index * 2 + 1] = (smoothed[index * 2 + 1] + 0.5f) * gridToImageY
        }
        return HoldOutline.Polygon(points)
    }

    // 후보 선택

    /** 가장 점수가 높은 후보를 고르되, 점수가 거의 같고 넓이가 1.6배 이상 작은 후보가 있으면 그쪽을 쓴다. */
    private fun choose(candidates: List<Candidate>): Candidate? {
        val best = candidates.maxByOrNull { it.score } ?: return null
        val smaller = candidates
            .filter { it.areaRatio * Tuning.PreferSmallerAreaRatio <= best.areaRatio }
            .filter { best.score - it.score <= Tuning.PreferSmallerMargin }
            .minByOrNull { it.areaRatio }
        return smaller ?: best
    }

    /** 경계 근처 logit이 임계값에 따라 얼마나 흔들리는지. SAM 자동 마스크의 안정도와 같은 정의다. */
    private fun stabilityScore(field: FloatArray, box: Bounds, gridWidth: Int, gridHeight: Int): Float {
        var strict = 0
        var loose = 0
        for (y in max(0, box.minY - 4)..min(gridHeight - 1, box.maxY + 4)) {
            for (x in max(0, box.minX - 4)..min(gridWidth - 1, box.maxX + 4)) {
                val value = field[y * gridWidth + x]
                if (value > Tuning.StabilityOffset) strict += 1
                if (value > -Tuning.StabilityOffset) loose += 1
            }
        }
        return if (loose > 0) strict.toFloat() / loose else 0f
    }

    // logit 격자

    /** 저해상도 logit을 격자 칸 중심에서 이중선형으로 읽는다. 256 칸은 1024 입력의 4px에 해당한다. */
    private fun sampledField(input: Input, channel: Int, gridWidth: Int, gridHeight: Int, gridScale: Float): FloatArray {
        val size = input.lowResSize
        val plane = channel * size * size
        val lowPerModel = size / 1024f
        val modelPerGrid = 1f / gridScale
        val logits = input.logits
        val field = FloatArray(gridWidth * gridHeight)
        for (y in 0 until gridHeight) {
            val ly = (y + 0.5f) * modelPerGrid * lowPerModel - 0.5f
            val y0 = floor(ly).toInt().coerceIn(0, size - 1)
            val y1 = min(size - 1, y0 + 1)
            val fy = (ly - y0).coerceIn(0f, 1f)
            for (x in 0 until gridWidth) {
                val lx = (x + 0.5f) * modelPerGrid * lowPerModel - 0.5f
                val x0 = floor(lx).toInt().coerceIn(0, size - 1)
                val x1 = min(size - 1, x0 + 1)
                val fx = (lx - x0).coerceIn(0f, 1f)
                val top = logits[plane + y0 * size + x0] * (1 - fx) + logits[plane + y0 * size + x1] * fx
                val bottom = logits[plane + y1 * size + x0] * (1 - fx) + logits[plane + y1 * size + x1] * fx
                field[y * gridWidth + x] = top * (1 - fy) + bottom * fy
            }
        }
        return field
    }

    // 영역 연산

    /** 탭한 칸과 4방향으로 이어진 영역. 탭이 영역 밖이면 search 칸 안의 가장 가까운 칸에서 시작한다. */
    private fun component(grid: Grid, tapX: Int, tapY: Int, search: Float): Grid? {
        var seed = -1
        if (grid[tapX, tapY]) {
            seed = tapY * grid.width + tapX
        } else if (search > 0f) {
            val radius = min(max(grid.width, grid.height).toFloat(), ceil(search)).toInt()
            var bestDistance = Int.MAX_VALUE
            for (y in max(0, tapY - radius)..min(grid.height - 1, tapY + radius)) {
                for (x in max(0, tapX - radius)..min(grid.width - 1, tapX + radius)) {
                    if (!grid[x, y]) continue
                    val distance = (x - tapX) * (x - tapX) + (y - tapY) * (y - tapY)
                    if (distance <= search * search && distance < bestDistance) {
                        bestDistance = distance
                        seed = y * grid.width + x
                    }
                }
            }
        }
        if (seed < 0) return null

        val region = Grid(grid.width, grid.height)
        val stack = IntStack()
        region.values[seed] = true
        stack.push(seed)
        while (stack.isNotEmpty()) {
            val index = stack.pop()
            val x = index % grid.width
            val y = index / grid.width
            fun visit(next: Int) {
                if (grid.values[next] && !region.values[next]) {
                    region.values[next] = true
                    stack.push(next)
                }
            }
            if (x > 0) visit(index - 1)
            if (x < grid.width - 1) visit(index + 1)
            if (y > 0) visit(index - grid.width)
            if (y < grid.height - 1) visit(index + grid.width)
        }
        return region
    }

    /** 바깥과 이어지지 않은 빈 칸(볼트 구멍, 초크 자국)을 채운다. */
    private fun filledHoles(grid: Grid): Grid {
        val width = grid.width
        val height = grid.height
        val outside = BooleanArray(width * height)
        val stack = IntStack()
        fun push(x: Int, y: Int) {
            val index = y * width + x
            if (!grid.values[index] && !outside[index]) {
                outside[index] = true
                stack.push(index)
            }
        }
        for (x in 0 until width) { push(x, 0); push(x, height - 1) }
        for (y in 0 until height) { push(0, y); push(width - 1, y) }
        while (stack.isNotEmpty()) {
            val index = stack.pop()
            val x = index % width
            val y = index / width
            if (x > 0) push(x - 1, y)
            if (x < width - 1) push(x + 1, y)
            if (y > 0) push(x, y - 1)
            if (y < height - 1) push(x, y + 1)
        }
        return Grid(width, height, BooleanArray(width * height) { !outside[it] })
    }

    /** 3-4 chamfer 거리. target 칸까지의 거리(칸 단위)를 돌려준다. */
    private fun distance(target: BooleanArray, width: Int, height: Int): FloatArray {
        val far = Int.MAX_VALUE / 2
        val distance = IntArray(width * height) { if (target[it]) 0 else far }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                var value = distance[index]
                if (value == 0) continue
                if (x > 0) value = min(value, distance[index - 1] + 3)
                if (y > 0) {
                    value = min(value, distance[index - width] + 3)
                    if (x > 0) value = min(value, distance[index - width - 1] + 4)
                    if (x < width - 1) value = min(value, distance[index - width + 1] + 4)
                }
                distance[index] = value
            }
        }
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val index = y * width + x
                var value = distance[index]
                if (value == 0) continue
                if (x < width - 1) value = min(value, distance[index + 1] + 3)
                if (y < height - 1) {
                    value = min(value, distance[index + width] + 3)
                    if (x < width - 1) value = min(value, distance[index + width + 1] + 4)
                    if (x > 0) value = min(value, distance[index + width - 1] + 4)
                }
                distance[index] = value
            }
        }
        return FloatArray(distance.size) { distance[it] / 3f }
    }

    private fun dilated(grid: Grid, radius: Float): Grid {
        val distance = distance(grid.values, grid.width, grid.height)
        return Grid(grid.width, grid.height, BooleanArray(distance.size) { distance[it] <= radius })
    }

    private fun eroded(grid: Grid, radius: Float): Grid {
        val outside = BooleanArray(grid.values.size) { !grid.values[it] }
        val distance = distance(outside, grid.width, grid.height)
        return Grid(grid.width, grid.height, BooleanArray(distance.size) { distance[it] > radius })
    }

    /** 열림: 반지름보다 가는 잔가지(옆 홀드로 새는 선, 볼트)를 뗀다. 영역이 절반 넘게 줄면 원래 영역을 쓴다. */
    private fun opened(grid: Grid, radius: Float, seedX: Int, seedY: Int, search: Float): Grid {
        val core = eroded(grid, radius)
        if (core.values.none { it }) return grid
        val reopened = dilated(core, radius + 0.5f)
        for (index in reopened.values.indices) if (!grid.values[index]) reopened.values[index] = false
        val kept = component(reopened, seedX, seedY, search) ?: return grid
        return if (kept.count * 2 > grid.count) kept else grid
    }

    private fun bounds(grid: Grid): Bounds? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                if (!grid[x, y]) continue
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        return if (minX == Int.MAX_VALUE) null else Bounds(minX, minY, maxX, maxY)
    }

    private fun centroid(grid: Grid, scaleX: Float, scaleY: Float): Pair<Float, Float> {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0.0
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                if (!grid[x, y]) continue
                sumX += x; sumY += y; count += 1
            }
        }
        if (count <= 0.0) return 0f to 0f
        return ((sumX / count).toFloat() + 0.5f) * scaleX to ((sumY / count).toFloat() + 0.5f) * scaleY
    }

    private fun circle(centerX: Float, centerY: Float, radius: Float, input: Input): HoldOutline.Circle =
        HoldOutline.Circle(
            centerX = centerX.coerceIn(radius, max(radius, input.imageWidth - radius)),
            centerY = centerY.coerceIn(radius, max(radius, input.imageHeight - radius)),
            radius = radius
        )

    // 윤곽선

    /** Moore 이웃 추적으로 바깥 경계 칸을 순서대로 모은다. 결과는 x, y가 번갈아 든 배열이다. */
    private fun traceBoundary(grid: Grid): FloatArray {
        var start = -1
        for (index in grid.values.indices) if (grid.values[index]) { start = index; break }
        if (start < 0) return FloatArray(0)
        val directionX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val directionY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        fun inside(x: Int, y: Int) = x in 0 until grid.width && y in 0 until grid.height && grid[x, y]
        val startX = start % grid.width
        val startY = start / grid.width
        val points = mutableListOf(startX.toFloat(), startY.toFloat())
        var currentX = startX
        var currentY = startY
        var back = 4
        repeat(grid.width * grid.height * 2) {
            var moved = false
            for (step in 0 until 8) {
                val direction = (back + 1 + step) % 8
                val nextX = currentX + directionX[direction]
                val nextY = currentY + directionY[direction]
                if (inside(nextX, nextY)) {
                    back = (direction + 4) % 8
                    currentX = nextX
                    currentY = nextY
                    moved = true
                    break
                }
            }
            if (!moved || (currentX == startX && currentY == startY)) return points.toFloatArray()
            points += currentX.toFloat()
            points += currentY.toFloat()
        }
        return points.toFloatArray()
    }

    /** 닫힌 윤곽선을 시작점과 가장 먼 점으로 나눠 Douglas–Peucker로 줄인다. */
    private fun simplifyClosed(points: FloatArray, tolerance: Float): FloatArray {
        val count = points.size / 2
        if (count < 4) return points
        var farIndex = 0
        var farDistance = -1f
        for (index in 0 until count) {
            val distance = hypot(points[index * 2] - points[0], points[index * 2 + 1] - points[1])
            if (distance > farDistance) {
                farDistance = distance
                farIndex = index
            }
        }
        if (farIndex == 0) return points
        val firstHalf = simplifyOpen(points.copyOfRange(0, (farIndex + 1) * 2), tolerance)
        val secondHalf = simplifyOpen(points.copyOfRange(farIndex * 2, points.size) + floatArrayOf(points[0], points[1]), tolerance)
        return firstHalf.copyOfRange(0, firstHalf.size - 2) + secondHalf.copyOfRange(0, secondHalf.size - 2)
    }

    private fun simplifyOpen(points: FloatArray, tolerance: Float): FloatArray {
        val count = points.size / 2
        if (count <= 2) return points
        val keep = BooleanArray(count)
        keep[0] = true
        keep[count - 1] = true
        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges.addLast(0 to count - 1)
        while (ranges.isNotEmpty()) {
            val (start, end) = ranges.removeLast()
            val ax = points[start * 2]
            val ay = points[start * 2 + 1]
            val dx = points[end * 2] - ax
            val dy = points[end * 2 + 1] - ay
            val length = hypot(dx, dy)
            var farthest = -1f
            var farthestIndex = -1
            for (index in start + 1 until end) {
                val px = points[index * 2]
                val py = points[index * 2 + 1]
                val distance = if (length < 1e-6f) hypot(px - ax, py - ay)
                else abs(dy * (px - ax) - dx * (py - ay)) / length
                if (distance > farthest) {
                    farthest = distance
                    farthestIndex = index
                }
            }
            if (farthest > tolerance && farthestIndex > 0) {
                keep[farthestIndex] = true
                ranges.addLast(start to farthestIndex)
                ranges.addLast(farthestIndex to end)
            }
        }
        val result = ArrayList<Float>(count * 2)
        for (index in 0 until count) if (keep[index]) {
            result += points[index * 2]
            result += points[index * 2 + 1]
        }
        return result.toFloatArray()
    }

    private fun chaikin(points: FloatArray, iterations: Int): FloatArray {
        if (points.size < 6) return points
        var result = points
        repeat(iterations) {
            val count = result.size / 2
            val next = FloatArray(count * 4)
            for (index in 0 until count) {
                val ax = result[index * 2]
                val ay = result[index * 2 + 1]
                val bx = result[((index + 1) % count) * 2]
                val by = result[((index + 1) % count) * 2 + 1]
                next[index * 4] = ax * 0.75f + bx * 0.25f
                next[index * 4 + 1] = ay * 0.75f + by * 0.25f
                next[index * 4 + 2] = ax * 0.25f + bx * 0.75f
                next[index * 4 + 3] = ay * 0.25f + by * 0.75f
            }
            result = next
        }
        return result
    }

    private class IntStack {
        private var storage = IntArray(1024)
        private var size = 0
        fun push(value: Int) {
            if (size == storage.size) storage = storage.copyOf(storage.size * 2)
            storage[size++] = value
        }
        fun pop(): Int = storage[--size]
        fun isNotEmpty(): Boolean = size > 0
    }
}
