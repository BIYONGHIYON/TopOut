package com.topout.cam.util

import android.content.Context

/** 이전 프로세스가 강제 종료되며 cache에 남긴 원본·내보내기 임시 영상을 정리한다. */
object TemporaryVideoCleaner {
    private val temporaryVideoName = Regex("""topout_(raw|export)_(\d+)\.mp4""")

    fun purgeStale(context: Context, createdBeforeMillis: Long) {
        context.cacheDir.listFiles()?.forEach { file ->
            val createdAt = temporaryVideoName.matchEntire(file.name)
                ?.groupValues
                ?.get(2)
                ?.toLongOrNull()
                ?: return@forEach
            // 현재 ViewModel 이후에 만든 녹화·내보내기 파일은 건드리지 않는다.
            if (createdAt < createdBeforeMillis) file.delete()
        }
    }
}
