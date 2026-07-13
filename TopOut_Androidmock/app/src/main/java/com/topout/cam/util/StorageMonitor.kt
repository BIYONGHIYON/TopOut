package com.topout.cam.util

import android.content.Context
import android.os.StatFs
import com.topout.cam.model.AppSettings
import com.topout.cam.model.TopOutConfig
import kotlin.math.max

object StorageMonitor {
    fun availableBytes(context: Context): Long? = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrNull()

    fun requiredSafetyBytes(settings: AppSettings): Long {
        val totalBitrate = settings.resolution.estimatedBitrate(settings.frameRate, settings.aspectRatio) + 96_000L
        val twoMinutes = totalBitrate / 8L * 120L
        return max(TopOutConfig.MinimumStorageBytes, twoMinutes + 256L * 1024L * 1024L)
    }
}
