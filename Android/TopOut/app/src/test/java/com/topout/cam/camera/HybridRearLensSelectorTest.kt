package com.topout.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HybridRearLensSelectorTest {
    private fun candidate(
        value: String,
        cameraId: String = value,
        physicalCameraId: String? = null,
        anchorRatio: Float = 1f,
        nativeMinZoom: Float = 1f,
        nativeMaxZoom: Float = 8f,
        isLogical: Boolean = false,
        members: Set<String> = emptySet()
    ) = HybridRearLensCandidate(
        value = value,
        cameraId = cameraId,
        physicalCameraId = physicalCameraId,
        anchorRatio = anchorRatio,
        nativeMinZoom = nativeMinZoom,
        nativeMaxZoom = nativeMaxZoom,
        equivalentFocalLength = REFERENCE_FOCAL_LENGTH * anchorRatio,
        isLogicalMultiCamera = isLogical,
        logicalPhysicalCameraIds = members
    )

    private fun select(candidates: List<HybridRearLensCandidate<String>>): List<String>? =
        selectHybridRearLensCandidates(
            candidates = candidates,
            referenceFocalLength = REFERENCE_FOCAL_LENGTH,
            expectedWideEquivalent = REFERENCE_FOCAL_LENGTH,
            boundaryEpsilon = 0.001f,
            transitionGap = 0.1f,
            maximumGapFraction = 0.25f
        )?.map { it.value }

    @Test
    fun allInOneLogicalCameraRemovesPinnedAndPublicMemberDuplicates() {
        val result = select(
            listOf(
                candidate(
                    value = "logical",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    isLogical = true,
                    members = setOf("ultra", "wide", "tele")
                ),
                candidate(
                    value = "pinned-tele",
                    cameraId = "0/tele",
                    physicalCameraId = "tele",
                    anchorRatio = 3f
                ),
                candidate(value = "public-tele", cameraId = "tele", anchorRatio = 3f)
            )
        )

        assertEquals(listOf("logical"), result)
    }

    @Test
    fun logicalWideGroupKeepsStandaloneTelephoto() {
        val result = select(
            listOf(
                candidate(
                    value = "logical-wide",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    isLogical = true,
                    members = setOf("ultra", "wide")
                ),
                candidate(value = "standalone-tele", cameraId = "3", anchorRatio = 3f)
            )
        )

        assertEquals(listOf("logical-wide", "standalone-tele"), result)
    }

    @Test
    fun logicalWideGroupKeepsMultipleStandaloneTelephotos() {
        val result = select(
            listOf(
                candidate(
                    value = "logical-wide",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    isLogical = true,
                    members = setOf("ultra", "wide")
                ),
                candidate(value = "tele-3x", cameraId = "3", anchorRatio = 3f),
                candidate(value = "tele-5x", cameraId = "5", anchorRatio = 5f)
            )
        )

        assertEquals(listOf("logical-wide", "tele-3x", "tele-5x"), result)
    }

    @Test
    fun selectedTeleLogicalGroupRemovesItsOwnHigherMember() {
        val result = select(
            listOf(
                candidate(
                    value = "logical-wide",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    isLogical = true,
                    members = setOf("ultra", "wide")
                ),
                candidate(
                    value = "logical-tele",
                    cameraId = "10",
                    anchorRatio = 3f,
                    isLogical = true,
                    members = setOf("periscope")
                ),
                candidate(
                    value = "pinned-periscope",
                    cameraId = "10/periscope",
                    physicalCameraId = "periscope",
                    anchorRatio = 5f
                ),
                candidate(value = "public-periscope", cameraId = "periscope", anchorRatio = 5f)
            )
        )

        assertEquals(listOf("logical-wide", "logical-tele"), result)
    }

    @Test
    fun publicTelephotoWinsOverPinnedCandidateAtSameAnchor() {
        val result = select(
            listOf(
                candidate(
                    value = "logical-wide",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    isLogical = true
                ),
                candidate(
                    value = "pinned-tele",
                    cameraId = "20/tele",
                    physicalCameraId = "tele",
                    anchorRatio = 3f
                ),
                candidate(value = "public-tele", cameraId = "tele", anchorRatio = 3f)
            )
        )

        assertEquals(listOf("logical-wide", "public-tele"), result)
    }

    @Test
    fun invalidLogicalCandidateDoesNotHideUsableCameraAtSameAnchor() {
        val result = select(
            listOf(
                candidate(
                    value = "logical-wide",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    isLogical = true
                ),
                candidate(
                    value = "invalid-logical-tele",
                    cameraId = "10",
                    anchorRatio = 3f,
                    nativeMinZoom = 1.2f,
                    isLogical = true
                ),
                candidate(value = "usable-public-tele", cameraId = "3", anchorRatio = 3f)
            )
        )

        assertEquals(listOf("logical-wide", "usable-public-tele"), result)
    }

    @Test
    fun unreachableTelephotoBoundaryIsNotExposed() {
        val result = select(
            listOf(
                candidate(
                    value = "logical-wide",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    nativeMaxZoom = 2f,
                    isLogical = true
                ),
                candidate(value = "standalone-tele", cameraId = "3", anchorRatio = 3f)
            )
        )

        assertEquals(listOf("logical-wide"), result)
    }

    @Test
    fun closeHigherAnchorUsesSameReducedGapAsGestureController() {
        val result = select(
            listOf(
                candidate(
                    value = "logical-wide",
                    cameraId = "0",
                    nativeMinZoom = 0.5f,
                    isLogical = true
                ),
                candidate(
                    value = "tele-3x",
                    cameraId = "3",
                    anchorRatio = 3f,
                    nativeMaxZoom = 3.1f / 3f
                ),
                candidate(value = "tele-3.2x", cameraId = "3.2", anchorRatio = 3.2f)
            )
        )

        assertEquals(listOf("logical-wide", "tele-3x"), result)
    }

    @Test
    fun noSubOneXLogicalCameraLeavesLegacyResolverInCharge() {
        val result = select(
            listOf(
                candidate(value = "wide", cameraId = "0"),
                candidate(value = "tele", cameraId = "3", anchorRatio = 3f)
            )
        )

        assertNull(result)
    }

    private companion object {
        const val REFERENCE_FOCAL_LENGTH = 26f
    }
}
