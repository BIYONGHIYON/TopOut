package com.topout.cam.camera

/** UI-facing events for one physical-camera transition. */
internal sealed interface LensTransitionEvent {
    /** Capture the currently displayed PreviewView frame exactly once. */
    data class Started(
        val id: Long,
        val displayZoom: Float,
        val frameScale: Float,
        val awaitingGestureEnd: Boolean
    ) : LensTransitionEvent

    /** Reuse the captured frame while its scale or gesture phase changes. */
    data class Updated(
        val id: Long,
        val displayZoom: Float,
        val frameScale: Float,
        val awaitingGestureEnd: Boolean
    ) : LensTransitionEvent

    /** The replacement/restored Preview is ready; fade the captured frame away. */
    data class Completed(
        val id: Long,
        val displayZoom: Float
    ) : LensTransitionEvent

    /** Cancellation or readiness failure; remove the captured frame immediately. */
    data class Aborted(
        val id: Long,
        val displayZoom: Float
    ) : LensTransitionEvent
}
