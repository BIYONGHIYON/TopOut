package com.topout.cam.sam

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.topout.cam.model.HoldMask

/** TopOut hold-selection boundary. */
interface HoldSegmenter : AutoCloseable {
    suspend fun segment(
        bitmap: Bitmap,
        normalizedTap: Offset
    ): Result<HoldMask>
    override fun close() = Unit
}
