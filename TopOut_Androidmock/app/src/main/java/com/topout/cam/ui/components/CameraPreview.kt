package com.topout.cam.ui.components

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.topout.cam.model.TopOutConfig

/**
 * Low-latency CameraX preview with native pinch zoom.
 *
 * ScaleGestureDetector runs inside PreviewView, so every accepted motion event is converted to a
 * zoom ratio immediately. One pinch is limited to one physical-lens section; releasing and
 * pinching again crosses the next optical anchor, where CameraEngine binds the matching camera.
 */
@Composable
fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
    onPreviewReady: (LifecycleOwner, PreviewView) -> Unit,
    onPreviewDisposed: (() -> Unit)? = null,
    previewViewChanged: (PreviewView) -> Unit = {},
    zoomEnabled: Boolean = false,
    zoomTransitionInProgress: Boolean = false,
    zoomRebaseToken: Long = 0L,
    currentZoom: Float = 0.5f,
    minimumZoom: Float = 0.5f,
    maximumZoom: Float = TopOutConfig.CameraMaxZoom,
    lensZoomRatios: List<Float> = listOf(0.5f, 1f),
    /** TextureView is more reliable than SurfaceView inside the rotated Compose layout. */
    useCompatibleMode: Boolean = false,
    onZoomChanged: (Float) -> Unit = {}
) {
    val key = remember { Any() }
    val latestZoomEnabled = rememberUpdatedState(zoomEnabled)
    val latestZoomTransitionInProgress = rememberUpdatedState(zoomTransitionInProgress)
    val latestZoomRebaseToken = rememberUpdatedState(zoomRebaseToken)
    val latestCurrentZoom = rememberUpdatedState(currentZoom)
    val latestMinimumZoom = rememberUpdatedState(minimumZoom)
    val latestMaximumZoom = rememberUpdatedState(maximumZoom)
    val latestLensZoomRatios = rememberUpdatedState(lensZoomRatios)
    val latestOnZoomChanged = rememberUpdatedState(onZoomChanged)
    val latestPreviewViewChanged = rememberUpdatedState(previewViewChanged)
    val latestOnPreviewReady = rememberUpdatedState(onPreviewReady)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            var zoomController = LensZoomGestureController(
                minimumZoom = minimumZoom,
                maximumZoom = maximumZoom,
                lensAnchors = lensZoomRatios
            )
            var transitionWasLockedDuringGesture = false
            var gestureRebaseToken = zoomRebaseToken

            fun resetZoomControllerAtCurrentZoom() {
                val lower = latestMinimumZoom.value
                val upper = latestMaximumZoom.value.coerceAtLeast(lower)
                zoomController = LensZoomGestureController(
                    minimumZoom = lower,
                    maximumZoom = upper,
                    lensAnchors = latestLensZoomRatios.value
                )
                zoomController.begin(latestCurrentZoom.value)
            }

            val scaleDetector = ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        if (!latestZoomEnabled.value) return false
                        resetZoomControllerAtCurrentZoom()
                        gestureRebaseToken = latestZoomRebaseToken.value
                        transitionWasLockedDuringGesture =
                            latestZoomTransitionInProgress.value
                        return true
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        if (!latestZoomEnabled.value) return false

                        if (latestZoomTransitionInProgress.value) {
                            // Consume motion while CameraX is loading the next physical lens, but
                            // never add it to cumulativeScale. Otherwise the new camera can appear
                            // already zoomed well beyond its optical anchor.
                            transitionWasLockedDuringGesture = true
                            return true
                        }

                        val completedSwitchToken = latestZoomRebaseToken.value
                        if (
                            transitionWasLockedDuringGesture ||
                            completedSwitchToken != gestureRebaseToken
                        ) {
                            // The new preview is now streaming. Rebase this still-active pinch at
                            // the exact transition ratio (1.0x, 0.9x, 3.0x, ...), and discard the
                            // first post-load sample so movement during loading cannot leak in.
                            // The token also handles very fast switches where Compose coalesces
                            // the temporary `transitionInProgress=true` state.
                            resetZoomControllerAtCurrentZoom()
                            gestureRebaseToken = completedSwitchToken
                            transitionWasLockedDuringGesture = false
                            return true
                        }

                        latestOnZoomChanged.value(zoomController.update(detector.scaleFactor))
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        zoomController.end()
                        transitionWasLockedDuringGesture = false
                    }
                }
            ).apply {
                // Avoid Android's one-finger double-tap quick-scale path. TopOut uses only the
                // predictable two-finger pinch gesture.
                isQuickScaleEnabled = false
            }

            PreviewView(context).apply {
                implementationMode = if (useCompatibleMode) {
                    PreviewView.ImplementationMode.COMPATIBLE
                } else {
                    PreviewView.ImplementationMode.PERFORMANCE
                }
                scaleType = PreviewView.ScaleType.FILL_CENTER
                isClickable = true
                setOnClickListener { /* Preview has no single-tap action. */ }
                setOnTouchListener { view, event ->
                    if (!latestZoomEnabled.value) {
                        false
                    } else {
                        scaleDetector.onTouchEvent(event)
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        true
                    }
                }

                val previewView = this
                doOnLayout {
                    // Bind only after AspectFrame has given PreviewView its final measured size.
                    // CameraEngine can then derive one exact ViewPort from the real preview frame.
                    latestPreviewViewChanged.value(previewView)
                    latestOnPreviewReady.value(lifecycleOwner, previewView)
                }
            }
        },
        update = {
            it.implementationMode = if (useCompatibleMode) {
                PreviewView.ImplementationMode.COMPATIBLE
            } else {
                PreviewView.ImplementationMode.PERFORMANCE
            }
            latestPreviewViewChanged.value(it)
        }
    )

    DisposableEffect(key) {
        onDispose { onPreviewDisposed?.invoke() }
    }
}
