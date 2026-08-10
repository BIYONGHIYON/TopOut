package com.topout.cam.camera

import kotlin.math.abs

/** Pure metadata used to resolve mixed logical-camera and standalone-telephoto topologies. */
internal data class HybridRearLensCandidate<T>(
    val value: T,
    val cameraId: String,
    val physicalCameraId: String?,
    val anchorRatio: Float,
    val nativeMinZoom: Float,
    val nativeMaxZoom: Float,
    val equivalentFocalLength: Float,
    val isLogicalMultiCamera: Boolean,
    val logicalPhysicalCameraIds: Set<String> = emptySet()
)

/**
 * Returns null when there is no public logical camera spanning below 1x, leaving the fully separate
 * physical-camera resolver unchanged. Otherwise it keeps that logical camera as the base route and
 * appends only higher optical cameras that are not members of any selected logical group.
 */
internal fun <T> selectHybridRearLensCandidates(
    candidates: List<HybridRearLensCandidate<T>>,
    referenceFocalLength: Float,
    expectedWideEquivalent: Float,
    boundaryEpsilon: Float,
    transitionGap: Float,
    maximumGapFraction: Float
): List<HybridRearLensCandidate<T>>? {
    if (!referenceFocalLength.isFinite() || referenceFocalLength <= 0f) return null

    val seamlessBase = candidates
        .asSequence()
        .filter { candidate ->
            abs(candidate.anchorRatio - 1f) <= boundaryEpsilon &&
                candidate.physicalCameraId == null &&
                candidate.isLogicalMultiCamera &&
                candidate.nativeMinZoom < 0.95f
        }
        .minWithOrNull(
            compareBy<HybridRearLensCandidate<T>> { it.nativeMinZoom }
                .thenBy { abs(it.equivalentFocalLength - expectedWideEquivalent) }
                .thenByDescending { it.nativeMaxZoom }
                .thenBy { it.cameraId }
        ) ?: return null

    val selected = mutableListOf(seamlessBase)
    val coveredPhysicalIds = seamlessBase.logicalPhysicalCameraIds.toMutableSet()
    val higherAnchors = candidates
        .asSequence()
        .filter { it.anchorRatio > 1f + boundaryEpsilon }
        .groupBy { it.anchorRatio }
        .toSortedMap()

    for ((anchor, sameAnchor) in higherAnchors) {
        val available = sameAnchor.filter { candidate ->
            candidate.cameraId !in coveredPhysicalIds &&
                candidate.physicalCameraId !in coveredPhysicalIds &&
                candidate.nativeMinZoom <= 1f + boundaryEpsilon
        }
        val chosen = available.minWithOrNull(
            compareBy<HybridRearLensCandidate<T>> { candidate ->
                when {
                    candidate.physicalCameraId == null && candidate.isLogicalMultiCamera -> 0
                    candidate.physicalCameraId == null -> 1
                    else -> 2
                }
            }
                .thenBy { abs(it.equivalentFocalLength / referenceFocalLength - it.anchorRatio) }
                .thenByDescending { it.nativeMaxZoom }
                .thenBy { it.cameraId }
        ) ?: continue

        val previous = selected.last()
        val previousDisplayMax = previous.anchorRatio * previous.nativeMaxZoom
        val previousGestureAnchor = if (selected.size == 1) {
            previous.anchorRatio * previous.nativeMinZoom
        } else {
            previous.anchorRatio
        }
        val safeGap = minOf(
            transitionGap,
            (anchor - previousGestureAnchor) * maximumGapFraction
        )
        val requiredPreviousZoom = (anchor - safeGap).coerceAtLeast(previousGestureAnchor)
        if (previousDisplayMax + boundaryEpsilon < requiredPreviousZoom) {
            // The current camera would clamp while the UI continued toward this anchor. Do not
            // expose this or any higher route unless the preceding route can actually reach it.
            break
        }
        selected += chosen
        coveredPhysicalIds += chosen.logicalPhysicalCameraIds
        chosen.physicalCameraId?.let(coveredPhysicalIds::add)
    }

    return selected
}
