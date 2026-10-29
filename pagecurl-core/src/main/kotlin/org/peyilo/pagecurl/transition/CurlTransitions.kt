package org.peyilo.pagecurl.transition

import kotlin.math.max
import org.peyilo.pagecurl.control.CurlControl
import org.peyilo.pagecurl.model.CurlConfig
import org.peyilo.pagecurl.model.CurlKind

// Lifecycle codes are also used in compatibility and completion checks.
// Do not renumber them independently of the state-machine branches below.
private const val AUTOMATIC_RUNNING = 1
private const val MANUAL_DRAGGING = 2
private const val STOPPING_STATE_THRESHOLD = 3
private const val ANIMATION_STOPPED = 4
private const val MANUAL_RELEASED = 5
private const val MAX_TRANSITIONS = 256
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val SECONDS_PER_NANO = 1e-9

/**
 * Mutable lifecycle record owned jointly by the scheduler and host animation adapter.
 *
 * @property id Host-provided transition identifier; keep it unique among live transitions.
 * @property type Effective CurlKind code, set when enqueued.
 * @property direction CurlDirection code, set when enqueued.
 * @property state Lifecycle code: 1 automatic running, 2 manual dragging, 4 animation stopped, 5 manual released.
 * @property willComplete Whether settling should commit the page turn.
 * @property finished Whether the animation adapter reported normal completion.
 * @property active Whether the transition has not yet been removed from active processing.
 * @property completionCount Outstanding animation callbacks; the host sets it for multi-part animations.
 * @property completed Internal flag marking a transition ready for ordered cleanup.
 * @property doubleSided Whether cleanup must remove two face layers.
 * @property beginSeconds Scheduled start time on the host's monotonic clock.
 */
data class CurlTransitionState(
    val id: Long,
    var type: Int,
    var direction: Int,
    var state: Int = AUTOMATIC_RUNNING,
    var willComplete: Boolean = true,
    var finished: Boolean = false,
    var active: Boolean = true,
    var completionCount: Int = 0,
    var completed: Boolean = false,
    var doubleSided: Boolean = false,
    var beginSeconds: Double = 0.0,
)

/**
 * Result of an enqueue policy decision; the host performs the actual animation work.
 *
 * @property accepted Whether the request was accepted.
 * @property type Resolved control mode.
 * @property queued Whether it waits for all active transitions to drain.
 * @property began Whether it was admitted to the active set, possibly with a future start time.
 * @property scheduled Whether it overlaps a compatible predecessor with a delay.
 * @property assertion Whether an incompatible manual request triggered the rejection path.
 * @property delayNanos Delay relative to the supplied current time, in nanoseconds.
 * @property previousId Predecessor identifier used to order overlapping layers.
 */
data class CurlTransitionDecision(
    val accepted: Boolean,
    val type: Int,
    val queued: Boolean = false,
    val began: Boolean = false,
    val scheduled: Boolean = false,
    val assertion: Boolean = false,
    val delayNanos: Long = 0,
    val previousId: Long = 0,
)

/**
 * Commands for the host layer adapter: create/order content, begin animation and deliver cleanup callbacks.
 * Their list order is significant; the core does not execute UI operations itself.
 */
enum class CurlLayerOperation {
    Add,
    Above,
    Below,
    AddContent,
    Begin,
    Remove,
    Finally,
    Completion,
}

/**
 * One command or notification for the host.
 *
 * @property state Transition identifier, not a lifecycle state code.
 * @property face Face index 0/1, or -1 for a transition-wide event.
 * @property relativeState Reference transition for Above/Below layer placement; zero means no predecessor.
 * @property relativeFace Reference face within that transition.
 * @property finished Whether the animation finished normally.
 * @property completed Whether the turn should commit, distinct from finished.
 */
data class CurlLayerEvent(
    val operation: CurlLayerOperation,
    val state: Long,
    val face: Int = -1,
    val relativeState: Long = 0,
    val relativeFace: Int = 0,
    val finished: Boolean = false,
    val completed: Boolean = false,
)

/**
 * Serial transition scheduler for overlapping automatic turns and one outstanding manual turn.
 * It produces decisions and ordered layer events; the host runs animations and reports
 * completion through animationStopped. All time inputs use one monotonic clock in seconds.
 * This class is mutable and must be accessed serially.
 *
 * @property delayBetweenSeconds Minimum spacing between compatible overlapping starts.
 */
class CurlTransitions(val delayBetweenSeconds: Double = 0.0) {
    // These accessors copy the list container; the contained lifecycle records remain mutable.
    val active: List<CurlTransitionState>
        get() = activeStates.toList()

    val pending: List<CurlTransitionState>
        get() = pendingStates.toList()

    private val activeStates = mutableListOf<CurlTransitionState>()
    private val pendingStates = mutableListOf<CurlTransitionState>()
    private var manual: CurlTransitionState? = null

    /**
     * Admits, schedules, queues or rejects a turn, initializing the supplied state on acceptance.
     * now is monotonic seconds. A delayed accepted turn belongs to active immediately;
     * the host must honor the returned delay before starting its animation.
     */
    fun enqueue(
        config: CurlConfig,
        state: CurlTransitionState,
        now: Double,
    ): CurlTransitionDecision {
        check(activeStates.size < MAX_TRANSITIONS && pendingStates.size < MAX_TRANSITIONS)
        val old = activeStates.lastOrNull()
        val decision =
            decide(
                config,
                old,
                activeStates.size,
                pendingStates.size,
                manual != null,
                if (old == null) 0.0 else now - old.beginSeconds,
                delayBetweenSeconds,
            )
        if (!decision.accepted) return decision
        state.type = decision.type
        state.direction = config.direction.nativeValue
        state.state = if (automatic(state.type)) AUTOMATIC_RUNNING else MANUAL_DRAGGING
        state.willComplete = true
        state.finished = false
        state.active = true
        state.completed = false
        state.doubleSided = config.doubleSided
        if (!automatic(state.type)) manual = state
        if (decision.queued) pendingStates += state
        else {
            state.beginSeconds = now + decision.delayNanos * SECONDS_PER_NANO
            activeStates += state
        }
        return decision
    }

    /**
     * Marks a manual turn as released and records commit/cancel intent.
     * The host must still settle its animation and deliver the completion callback.
     */
    fun endManual(state: CurlTransitionState, complete: Boolean): Boolean {
        if (automatic(state.type) || !setState(state, MANUAL_RELEASED, complete)) return false
        if (manual === state) manual = null
        return true
    }

    /**
     * Reports one host animation callback and returns ordered completion/layer events.
     * Outstanding callbacks are counted down before cleanup. Later completed turns wait
     * for older active turns; when the active set drains, the first queued turn begins.
     */
    fun animationStopped(
        state: CurlTransitionState,
        finished: Boolean,
        now: Double,
    ): List<CurlLayerEvent> {
        if (activeStates.none { it === state }) return emptyList()
        if (state.completionCount > 0) state.completionCount--
        if (state.completionCount != 0) return emptyList()
        if (state.state or ANIMATION_STOPPED != MANUAL_RELEASED) {
            if (state.state >= STOPPING_STATE_THRESHOLD) state.state = ANIMATION_STOPPED
            return emptyList()
        }
        state.finished = finished
        if (!automatic(state.type) && state.state != MANUAL_RELEASED) return emptyList()
        state.completed = true

        val events = mutableListOf<CurlLayerEvent>()
        fun event(op: CurlLayerOperation, s: CurlTransitionState, face: Int = -1) {
            events +=
                CurlLayerEvent(
                    op,
                    s.id,
                    face,
                    finished = s.finished,
                    completed = s.willComplete,
                )
        }
        // Retire in admission order, even if host animation callbacks arrive out of order.
        while (activeStates.firstOrNull()?.completed == true) {
            val head = activeStates.removeAt(0)
            event(
                CurlLayerOperation.Completion,
                head,
            )
            head.active = false
            event(CurlLayerOperation.Remove, head, 0)
            if (head.doubleSided)
                event(
                    CurlLayerOperation.Remove,
                    head,
                    1,
                )
            event(CurlLayerOperation.Finally, head)
            head.completed = false
            if (manual === head) manual = null
        }
        if (activeStates.isEmpty() && pendingStates.isNotEmpty()) {
            val next = pendingStates.removeAt(0)
            activeStates += next
            next.beginSeconds = now
            event(CurlLayerOperation.Begin, next)
        }
        return events
    }

    companion object {
        /**
         * Tests the automatic control modes used by the scheduling policy.
         */
        private fun automatic(type: Int) = CurlKind.isAutomatic(type)

        /**
         * Allows overlap only after automatic or released-manual work with matching reverse class and direction.
         */
        fun compatible(old: CurlTransitionState, type: Int, direction: Int) =
            (automatic(old.type) || old.state == MANUAL_RELEASED) &&
                ((type <= 2 && old.type < 3) || (type >= 3 && old.type >= 3)) &&
                old.direction == direction

        /**
         * Applies a legal lifecycle advance and stores the requested commit/cancel result.
         */
        fun setState(state: CurlTransitionState, newState: Int, complete: Boolean): Boolean {
            if (
                if (automatic(state.type)) state.state != AUTOMATIC_RUNNING
                else newState < state.state
            )
                return false
            state.state = newState
            state.willComplete = complete
            return true
        }

        /**
         * Pure scheduling policy: choose immediate admission, delayed overlap, queueing or rejection.
         * elapsed and delay are seconds; no collections or supplied state are mutated.
         */
        fun decide(
            config: CurlConfig,
            old: CurlTransitionState?,
            activeCount: Int,
            pendingCount: Int,
            manual: Boolean,
            elapsed: Double,
            delay: Double,
        ): CurlTransitionDecision {
            val type = CurlControl.type(config)
            if (!automatic(type) && (pendingCount != 0 || manual))
                return CurlTransitionDecision(
                    false,
                    type,
                )
            if (activeCount == 0 && pendingCount == 0)
                return CurlTransitionDecision(
                    true,
                    type,
                    began = true,
                )
            if (
                pendingCount != 0 ||
                    old == null ||
                    !compatible(
                        old,
                        type,
                        config.direction.nativeValue,
                    )
            )
                return if (!automatic(type))
                    CurlTransitionDecision(
                        false,
                        type,
                        assertion = true,
                    )
                else CurlTransitionDecision(true, type, queued = true)
            return CurlTransitionDecision(
                true,
                type,
                began = true,
                scheduled = true,
                delayNanos = (max(delay - elapsed, 0.0) * NANOS_PER_SECOND).toLong(),
                previousId = old.id,
            )
        }

        /**
         * Builds initial face insertion and content commands around an optional active predecessor.
         * The host executes these commands before starting the corresponding animation.
         */
        fun beginEvents(
            type: Int,
            doubleSided: Boolean,
            id: Long,
            previousId: Long = 0,
            previousActive: Boolean = false,
        ): List<CurlLayerEvent> = buildList {
            for (face in 0 until if (doubleSided) 2 else 1) {
                val operation =
                    if (previousId == 0L || !previousActive)
                        if (face == 0) CurlLayerOperation.Add else CurlLayerOperation.Above
                    else if (type <= 2)
                        if (face == 0) CurlLayerOperation.Below else CurlLayerOperation.Above
                    else if (face == 0) CurlLayerOperation.Above else CurlLayerOperation.Below
                val relative =
                    if (previousId == 0L || !previousActive) if (face == 0) 0 else id
                    else previousId
                add(
                    CurlLayerEvent(
                        operation,
                        id,
                        face,
                        relative,
                        if (previousId == 0L || !previousActive) 0 else face,
                    )
                )
                add(CurlLayerEvent(CurlLayerOperation.AddContent, id, face))
            }
        }
    }
}
