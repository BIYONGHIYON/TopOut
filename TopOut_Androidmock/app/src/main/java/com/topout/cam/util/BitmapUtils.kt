package com.topout.cam.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import com.topout.cam.model.HoldMask
import com.topout.cam.model.TopOutConfig
import kotlin.math.max
import kotlin.math.min

fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun createHoldOverlayBitmap(width: Int, height: Int, mask: HoldMask?): Bitmap? {
    mask ?: return null
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val contour = mask.normalizedContour.ifEmpty {
        listOf(
            Offset(mask.center.x - mask.radiusX, mask.center.y),
            Offset(mask.center.x, mask.center.y - mask.radiusY),
            Offset(mask.center.x + mask.radiusX, mask.center.y),
            Offset(mask.center.x, mask.center.y + mask.radiusY)
        )
    }
    val path = Path()
    contour.forEachIndexed { index, point ->
        val x = point.x.coerceIn(0f, 1f) * width
        val y = point.y.coerceIn(0f, 1f) * height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = max(TopOutConfig.MinWhiteLineWidth, min(width, height) * TopOutConfig.WhiteLineRatio)
        strokeJoin = Paint.Join.ROUND
    }
    val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = max(TopOutConfig.MinRedLineWidth, min(width, height) * TopOutConfig.RedLineRatio)
        strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(path, red)
    canvas.drawPath(path, white)
    return bitmap
}
