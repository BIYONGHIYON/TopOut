package com.topout.cam.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.topout.cam.model.AppSettings
import com.topout.cam.model.RecordingAspectRatio
import com.topout.cam.model.RecordingFrameRate
import com.topout.cam.model.RecordingResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.topOutDataStore by preferencesDataStore("topout_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val resolution = intPreferencesKey("resolution")
        val frameRate = intPreferencesKey("frame_rate")
        val aspectRatio = intPreferencesKey("aspect_ratio")
        val autoClip = booleanPreferencesKey("auto_clip")
        val topHoldMask = booleanPreferencesKey("top_hold_mask")
        val help = booleanPreferencesKey("help")
        val livePreviewDuringRecording = booleanPreferencesKey("live_preview_during_recording")
    }

    val settings: Flow<AppSettings> = context.topOutDataStore.data.map { prefs ->
        AppSettings(
            resolution = RecordingResolution.entries.getOrElse(prefs[Keys.resolution] ?: 0) { RecordingResolution.FHD },
            frameRate = RecordingFrameRate.entries.firstOrNull { it.fps == (prefs[Keys.frameRate] ?: 60) }
                ?: RecordingFrameRate.FPS60,
            aspectRatio = RecordingAspectRatio.entries.getOrElse(prefs[Keys.aspectRatio] ?: 0) { RecordingAspectRatio.STORY_9_16 },
            autoClipDetectionEnabled = prefs[Keys.autoClip] ?: true,
            topHoldMaskingEnabled = prefs[Keys.topHoldMask] ?: false,
            helpEnabled = prefs[Keys.help] ?: true,
            livePreviewDuringRecordingEnabled = prefs[Keys.livePreviewDuringRecording] ?: false
        ).normalized()
    }

    suspend fun save(value: AppSettings) {
        val normalized = value.normalized()
        context.topOutDataStore.edit { prefs ->
            prefs[Keys.resolution] = normalized.resolution.ordinal
            prefs[Keys.frameRate] = normalized.frameRate.fps
            prefs[Keys.aspectRatio] = normalized.aspectRatio.ordinal
            prefs[Keys.autoClip] = normalized.autoClipDetectionEnabled
            prefs[Keys.topHoldMask] = normalized.topHoldMaskingEnabled
            prefs[Keys.help] = normalized.helpEnabled
            prefs[Keys.livePreviewDuringRecording] = normalized.livePreviewDuringRecordingEnabled
        }
    }
}
