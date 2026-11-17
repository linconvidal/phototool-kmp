package br.com.lincon.phototool.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import kotlin.math.abs

private enum class DetailGestureAxis { HORIZONTAL, VERTICAL, AMBIGUOUS }

/** One recognizer arbitrates Fit navigation, compact panels and image transforms per contact. */
internal suspend fun PointerInputScope.detectDetailPhotoGestures(
    isFit: () -> Boolean,
    navigationThreshold: Float,
    onDrag: (Float, Boolean) -> Unit,
    onNavigate: (Int) -> Unit,
    onTransform: (Offset, Offset, Float) -> Unit,
    panelThreshold: Float = navigationThreshold,
    onVerticalSwipe: ((Int) -> Unit)? = null,
    onPanelDrag: (Float, Boolean) -> Unit = { _, _ -> },
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    val startedFit = isFit()
    var multiplePointers = false
    var axis: DetailGestureAxis? = null
    var displacement = Offset.Zero
    var accumulatedZoom = 1f
    var transformStarted = false
    try {
        while (true) {
            val event = awaitPointerEvent()
            // A canceled sequence (including platform pointercancel) must never commit a swipe.
            if (event.isCanceledContact() || event.changes.any { it.isConsumed }) break
            multiplePointers = multiplePointers || event.changes.count { it.pressed || it.previousPressed } > 1
            val change = event.changes.firstOrNull { it.id == down.id }
            val pan = event.calculatePan()
            displacement += pan
            if (!startedFit || multiplePointers) {
                onPanelDrag(0f, false)
                onDrag(0f, false)
                val zoom = event.calculateZoom()
                accumulatedZoom *= zoom
                if (!transformStarted) {
                    val zoomMotion = abs(1 - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
                    transformStarted = zoomMotion > viewConfiguration.touchSlop || displacement.getDistance() > viewConfiguration.touchSlop
                }
                if (transformStarted && event.changes.any { it.pressed }) {
                    onTransform(event.calculateCentroid(useCurrent = false), pan, zoom)
                    event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                }
            } else if (down.type == PointerType.Touch && change != null) {
                displacement = change.position - down.position
                if (axis == null && displacement.getDistance() > viewConfiguration.touchSlop) {
                    axis = when {
                        abs(displacement.x) > abs(displacement.y) * 1.5f -> DetailGestureAxis.HORIZONTAL
                        abs(displacement.y) > abs(displacement.x) * 1.5f -> DetailGestureAxis.VERTICAL
                        else -> DetailGestureAxis.AMBIGUOUS
                    }
                }
                if (axis == DetailGestureAxis.HORIZONTAL) {
                    onDrag(displacement.x, true)
                    if (change.changedToUp()) {
                        if (abs(displacement.x) >= navigationThreshold && abs(displacement.x) > abs(displacement.y) * 1.5f) {
                            onNavigate(if (displacement.x < 0) 1 else -1)
                        }
                    }
                    change.consume()
                } else if (axis == DetailGestureAxis.VERTICAL && onVerticalSwipe != null) {
                    onPanelDrag(displacement.y, true)
                    if (change.changedToUp() && abs(displacement.y) >= panelThreshold &&
                        abs(displacement.y) > abs(displacement.x) * 1.5f) {
                        onVerticalSwipe(if (displacement.y < 0) -1 else 1)
                    }
                    change.consume()
                }
            }
            if (event.changes.none { it.pressed }) break
        }
    } finally {
        onPanelDrag(0f, false)
        onDrag(0f, false)
    }
}
