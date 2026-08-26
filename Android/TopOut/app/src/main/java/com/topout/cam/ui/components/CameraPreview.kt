package com.topout.cam.ui.components

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
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
 * zoom ratio immediately. A zoom-in crossing keeps scaling a captured frame until finger release,
 * then CameraEngine binds the next physical lens at that final ratio. Zoom-out retains the
 * immediate boundary switch and resumes after the replacement Preview is streaming.
 */
@Composable
fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
    onPreviewReady: (LifecycleOwner, PreviewView) -> Unit,
    previewViewChanged: (PreviewView) -> Unit = {},
    zoomEnabled: Boolean = false,
    zoomTransitionInProgress: Boolean = false,
    zoomTransitionAwaitingGestureEnd: Boolean = false,
    zoomRebaseToken: Long = 0L,
    currentZoom: Float = 0.5f,
    minimumZoom: Float = 0.5f,
    maximumZoom: Float = TopOutConfig.CameraMaxZoom,
    lensZoomRatios: List<Float> = listOf(0.5f, 1f),
    /** TextureView is more reliable than SurfaceView inside the rotated Compose layout. */
    useCompatibleMode: Boolean = false,
    onZoomChanged: (Float) -> Unit = {},
    onDeferredZoomChanged: (Float) -> Unit = {},
    onZoomGestureEnded: () -> Unit = {}
) {
    val latestZoomEnabled = rememberUpdatedState(zoomEnabled)
    val latestZoomTransitionInProgress = rememberUpdatedState(zoomTransitionInProgress)
    val latestZoomTransitionAwaitingGestureEnd =
        rememberUpdatedState(zoomTransitionAwaitingGestureEnd)
    val latestZoomRebaseToken = rememberUpdatedState(zoomRebaseToken)
    val latestCurrentZoom = rememberUpdatedState(currentZoom)
    val latestMinimumZoom = rememberUpdatedState(minimumZoom)
    val latestMaximumZoom = rememberUpdatedState(maximumZoom)
    val latestLensZoomRatios = rememberUpdatedState(lensZoomRatios)
    val latestOnZoomChanged = rememberUpdatedState(onZoomChanged)
    val latestOnDeferredZoomChanged = rememberUpdatedState(onDeferredZoomChanged)
    val latestOnZoomGestureEnded = rememberUpdatedState(onZoomGestureEnded)
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
                            transitionWasLockedDuringGesture = true
                            if (latestZoomTransitionAwaitingGestureEnd.value) {
                                // 확대 중에는 정지 프레임을 유지하고 손을 뗀 뒤 카메라를 전환한다.
                                latestOnDeferredZoomChanged.value(
                                    zoomController.updatePendingTransition(detector.scaleFactor)
                                )
                            }
                            return true
                        }

                        val completedSwitchToken = latestZoomRebaseToken.value
                        if (
                            transitionWasLockedDuringGesture ||
                            completedSwitchToken != gestureRebaseToken
                        ) {
                            // 새 미리보기 시작 뒤 전환 배율에서 핀치를 다시 잡고 첫 입력은 버린다.
                            resetZoomControllerAtCurrentZoom()
                            gestureRebaseToken = completedSwitchToken
                            transitionWasLockedDuringGesture = false
                            return true
                        }

                        // 상태 전달이 한 프레임 늦어도 진행 중인 전환 입력은 이어서 처리한다.
                        if (zoomController.hasContinuablePendingTransition()) {
                            latestOnDeferredZoomChanged.value(
                                zoomController.updatePendingTransition(detector.scaleFactor)
                            )
                            return true
                        }

                        latestOnZoomChanged.value(zoomController.update(detector.scaleFactor))
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        zoomController.end()
                        transitionWasLockedDuringGesture = false
                        // 지연된 확대 전환이 없으면 카메라 엔진에서 무시한다.
                        latestOnZoomGestureEnded.value()
                    }
                }
            ).apply {
                // 한 손가락 빠른 확대를 막고 두 손가락 핀치만 사용한다.
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
                    // 최종 화면 크기가 정해진 뒤 카메라 화면 영역을 계산해 연결한다.
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
        }
    )

}
