package com.topout.cam.sam

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.topout.cam.model.HoldMask

/** TopOut hold-selection boundary. */
interface HoldSegmenter : AutoCloseable {
    suspend fun warmUp(bitmap: Bitmap): Result<Unit> = Result.success(Unit)
    suspend fun segment(
        bitmap: Bitmap,
        normalizedTap: Offset,
        selectionZoom: Float = 1f
    ): Result<HoldMask>
    override fun close() = Unit
}
