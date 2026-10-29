package org.peyilo.pagecurl.gesture

import kotlin.math.sqrt
import org.peyilo.pagecurl.model.CurlPoint

/**
 * Stateful single-gesture tap recognizer.
 * A tap must end within 1.5 seconds and remain within 45 logical units times sloppiness.
 * Use a new instance after a terminal state; feed timestamps from one monotonic clock.
 *
 * @property sloppiness Multiplier for the permitted movement distance, normally 1.0.
 */
class CurlTap(var sloppiness: Double = 1.0) {
    private companion object {
        const val MAX_TAP_DURATION_SECONDS = 1.5
        const val MAX_MOVEMENT_POINTS = 45.0
    }

    /** Current recognition result; Ended means a valid tap was recognized. */
    var state = CurlRecognition.Possible
        private set

    private var activePointer: Int? = null
    private var deadline = Double.POSITIVE_INFINITY
    private var start = CurlPoint(0.0, 0.0)

    /**
     * Processes the deadline even when no touch event arrives.
     *
     * @param time Monotonic time in seconds, using the same origin as touch events.
     * @param inclusive Whether an event exactly at the deadline counts as timed out.
     */
    fun advance(time: Double, inclusive: Boolean = true): CurlRecognition {
        if (
            state == CurlRecognition.Possible &&
                activePointer != null &&
                (if (inclusive) time >= deadline else time > deadline)
        )
            state = CurlRecognition.Failed
        return state
    }

    /**
     * Consumes a touch event after processing its timestamp against the deadline.
     *
     * @param timersFirst Resolves an exact deadline tie: true expires before handling the event.
     */
    fun event(event: CurlTouch, timersFirst: Boolean = true): CurlRecognition {
        advance(event.seconds, timersFirst)
        if (state.nativeValue >= 3) return state
        when (event.kind) {
            CurlTouchKind.Down ->
                if (activePointer != null) {
                    if (event.pointer != activePointer) state = CurlRecognition.Failed
                } else {
                    activePointer = event.pointer
                    start = event.point
                    deadline = event.seconds + MAX_TAP_DURATION_SECONDS
                }

            CurlTouchKind.Move ->
                if (activePointer == event.pointer) {
                    val dx = event.point.x - start.x
                    val dy = event.point.y - start.y
                    if (sqrt(dx * dx + dy * dy) > MAX_MOVEMENT_POINTS * sloppiness)
                        state = CurlRecognition.Failed
                }

            CurlTouchKind.Up ->
                if (activePointer == event.pointer) {
                    activePointer = null
                    deadline = Double.POSITIVE_INFINITY
                    state = CurlRecognition.Ended
                }

            CurlTouchKind.Cancel ->
                if (activePointer == event.pointer) {
                    activePointer = null
                    deadline = Double.POSITIVE_INFINITY
                    state = CurlRecognition.Failed
                }
        }
        return state
    }
}
