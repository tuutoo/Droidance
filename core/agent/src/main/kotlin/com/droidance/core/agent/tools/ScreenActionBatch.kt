// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.ToolEventStatus
import com.droidance.core.agent.ToolResultStatus
import com.droidance.core.agent.config.ScreenActionBatchPolicy
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

data class MappedScreenAction(
    val sourceOperation: String,
    val operation: ScreenBatchOperation,
    val argumentsJson: String,
    val waitMs: Long? = null,
    val targetId: String? = null,
    val targetX: Int? = null,
    val targetY: Int? = null,
    val actionIndex: Int = 0,
    val focusFrom: Int? = null,
    /** Full control bounds in the decision frame's normalized coordinates, never a fresh frame. */
    val gestureRegion: ModelCoordinateMapper.Bounds? = null
) {
    /**
     * A click executed right after another click in the same batch may first meet that click's
     * press feedback, on the same control or a neighbouring one. That change is not a stale view.
     */
    fun followsClick(previous: MappedScreenAction?): Boolean =
        operation == ScreenBatchOperation.CLICK && previous?.operation == ScreenBatchOperation.CLICK

    /** The capture a visit takes of the opened target, before it returns. */
    val capturesVisitTarget: Boolean
        get() = operation == ScreenBatchOperation.SCREENSHOT && sourceOperation.startsWith("visit[") &&
            sourceOperation.endsWith("].screenshot")

    /** The BACK that closes a visit; delivering it does not prove the origin view was restored. */
    val returnsFromVisit: Boolean
        get() = operation == ScreenBatchOperation.PRESS_KEY && sourceOperation.startsWith("visit[") &&
            sourceOperation.endsWith("].back")
}

enum class ScreenBatchOperation {
    CLICK,
    TYPE_TEXT,
    PRESS_KEY,
    SCROLL,
    SWIPE,
    DRAG,
    PATH,
    WAIT,
    SCREENSHOT
}

/** A captured page may refresh a planned scroll, never authorize an unseen control. */
class ScreenBatchScrollCheckpoint(
    val reference: ObservationPixelReference,
    val capturedAtElapsedMs: Long
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ScreenBatchScrollCheckpoint>
}

/**
 * Invocation-local grounding for repeated collection in one viewport. A batch made only of
 * scroll, screenshot and wait collects from the already-inspected frame. A batch that first
 * acts (a tab, sort or navigation) and then collects starts its collection segment at the
 * screenshot taken after the last action: that fresh frame, not the pre-action decision frame,
 * grounds the following scrolls.
 */
class ScreenBatchScrollGrounding(
    plan: ScreenActionBatchPlan,
    private var initial: com.droidance.core.agent.LocalActionEvidence.ObservationLite?
) {
    private val segmentStart: Int?
    private val wholeBatch: Boolean

    init {
        val actions = (plan as? ScreenActionBatchPlan.Ready)?.actions.orEmpty()
        val collectionOps = setOf(ScreenBatchOperation.SCROLL, ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.WAIT)
        val lastAction = actions.indexOfLast { it.operation !in collectionOps }
        val segment = actions.drop(lastAction + 1)
        val scrolls = segment.filter { it.operation == ScreenBatchOperation.SCROLL }
            .map { Json.parseToJsonElement(it.argumentsJson).jsonObject }
        val consistent = scrolls.isNotEmpty() &&
            scrolls.all { it["direction"] == scrolls.first()["direction"] && it["region"] == scrolls.first()["region"] }
        wholeBatch = consistent && lastAction < 0
        // After an action the segment must open with a screenshot, which becomes its grounding.
        val opening = segment.firstOrNull { it.operation != ScreenBatchOperation.WAIT }
        segmentStart = when {
            wholeBatch -> segment.first().actionIndex
            consistent && opening?.operation == ScreenBatchOperation.SCREENSHOT -> opening.actionIndex
            else -> null
        }
    }

    private var currentAction = if (wholeBatch) checkNotNull(segmentStart) else 0
    private var awaitingCapture = wholeBatch
    private var scrollDelivered = false
    var checkpoint: ScreenBatchScrollCheckpoint? = null
        private set
    val isCollection: Boolean get() = segmentStart != null && currentAction >= segmentStart
    val needsScrollRefresh: Boolean get() = isCollection && scrollDelivered && awaitingCapture

    /** Called before each action with its 1-based plan index. */
    fun before(actionIndex: Int) {
        currentAction = actionIndex
        if (!wholeBatch && actionIndex == segmentStart) {
            awaitingCapture = true
            initial = null
        }
    }

    fun scrollSettled(result: ToolExecutionResult) {
        checkpoint = null
        awaitingCapture = isCollection && result.executionStatus == ToolExecutionStatus.SUCCEEDED &&
            result.actionEvidence?.delivery == ActionDeliveryEvidence.ACTION_DELIVERED
        scrollDelivered = awaitingCapture
    }

    fun captured(observation: com.droidance.core.agent.LocalActionEvidence.ObservationLite,
                 reference: ObservationPixelReference?, capturedAtElapsedMs: Long) {
        // The segment's opening screenshot anchors the surface its scrolls must stay on.
        if (awaitingCapture && isCollection && initial == null && !wholeBatch) initial = observation
        val component = initial?.semanticState?.foregroundActivityOrWindow
        val sameSurface = initial?.backendSessionId != null && initial?.displayId != null &&
            component != null && observation.backendSessionId == initial?.backendSessionId &&
            observation.displayId == initial?.displayId && observation.semanticState?.foregroundActivityOrWindow == component
        checkpoint = if (awaitingCapture && isCollection && sameSurface && reference != null)
            ScreenBatchScrollCheckpoint(reference, capturedAtElapsedMs) else null
        awaitingCapture = false
    }
}

sealed interface ScreenActionBatchPlan {
    data class Ready(
        val actions: List<MappedScreenAction>,
        val observationId: String? = null
    ) : ScreenActionBatchPlan
    data class Rejected(val reason: String) : ScreenActionBatchPlan
}

object ScreenActionBatchPlanner {
    private val ROOT_KEYS = setOf("actions", "wait_for_frame_ms", "observation_id", "effect_key")
    private val ACTION_KEYS = ScreenActionBatchProtocol.actionFields

    fun plan(
        argumentsJson: String,
        policy: ScreenActionBatchPolicy
    ): ScreenActionBatchPlan {
        val root = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return rejected("arguments are not a JSON object")
        (root.keys - ROOT_KEYS).firstOrNull()?.let { key ->
            return rejected("unsupported batch property: $key")
        }
        val actions = root["actions"] as? JsonArray
            ?: return rejected("actions must be an array")
        if (actions.isEmpty()) return rejected("actions cannot be empty")
        if (!policy.enabled && actions.size > 1) return rejected("batching is disabled by harness policy")
        val objects = actions.mapIndexed { index, element ->
            element as? JsonObject ?: return rejected("action[$index] must be an object")
        }
        val operations = objects.mapIndexed { index, action ->
            action.string("op")
                ?: return rejected("action[$index].op is required")
        }
        operations.forEachIndexed { index, type ->
            if (type !in policy.allowedOperations || type !in ACTION_KEYS) {
                return rejected("action[$index] op=$type is not allowed")
            }
            val unsupportedKeys = objects[index].keys - (ACTION_KEYS[type] ?: setOf("op"))
            if (unsupportedKeys.isNotEmpty()) {
                return rejected(
                    "action[$index] op=$type contains unsupported property=" +
                        unsupportedKeys.sorted().first()
                )
            }
        }
        val primitives = try {
            expandHighLevel(objects, operations, policy)
        } catch (invalid: InvalidActionArgument) {
            return rejected(invalid.message.orEmpty())
        }
        val mapped = primitives.mapIndexed { index, (label, action) ->
            val type = checkNotNull(action.string("op"))
            if (type == "type_text" && "clear" !in action) {
                return rejected("action[$index] op=type_text requires clear (boolean)")
            }
            try {
                mapAction(type, action, policy).copy(actionIndex = index + 1, sourceOperation = label)
            } catch (invalid: InvalidActionArgument) {
                return rejected("action[$index] op=$label " + invalid.message)
            }
        }
        val bound = mapped.mapIndexed { index, action ->
            if (action.operation == ScreenBatchOperation.TYPE_TEXT && action.targetId == null &&
                action.targetX == null && index > 0 && mapped[index - 1].operation == ScreenBatchOperation.CLICK)
                action.copy(focusFrom = index) else action
        }
        if ("effect_key" in root && mapped.count {
            it.operation !in setOf(ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.WAIT)
        } > 1) {
            return rejected("effect_key identifies one committing action; observe preparatory edits in an earlier call")
        }
        val active = mapped.filter { it.operation !in setOf(ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.WAIT) }
        // A drag-only batch names its exact defect so the same Agent can repair it in one step.
        if (active.size > 1 && active.all { it.operation == ScreenBatchOperation.DRAG }) {
            if (active.any { it.gestureRegion == null })
                return rejected("action[${active.first().actionIndex - 1}] op=drag requires a new model observation before " +
                    "further actions; only drags with a declared region on every control may share a batch")
            active.firstOrNull { (Json.parseToJsonElement(it.argumentsJson).jsonObject.int("repeat") ?: 1) != 1 }?.let {
                return rejected("action[${it.actionIndex - 1}] op=drag needs repeat=1 to share a drag-only batch")
            }
            if (active.size > policy.maxRegionalDrags)
                return rejected("drag-only batch has ${active.size} drags; max_regional_drags=${policy.maxRegionalDrags}")
            active.forEachIndexed { index, action ->
                active.drop(index + 1).firstOrNull { other -> overlaps(checkNotNull(action.gestureRegion),
                    checkNotNull(other.gestureRegion)) }?.let { other ->
                    return rejected("action[${action.actionIndex - 1}] and action[${other.actionIndex - 1}] " +
                        "regions overlap; regional drags need non-overlapping regions")
                }
            }
        }
        val independentRegions = active.size in 2..policy.maxRegionalDrags && active.all {
            it.operation == ScreenBatchOperation.DRAG && it.gestureRegion != null
        }
        mapped.forEachIndexed { index, action ->
            if (action.operation.name.lowercase() in policy.decisionBoundaryOperations &&
                mapped.drop(index + 1).any { it.operation !in setOf(ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.WAIT) } &&
                !(action.operation == ScreenBatchOperation.DRAG && independentRegions)) {
                return rejected("action[$index] op=${action.sourceOperation} requires a new model observation before further actions")
            }
        }
        val frameWait = root["wait_for_frame_ms"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                ?.takeIf { it in 0..policy.maxWaitMs }
                ?: return rejected("wait_for_frame_ms must be an integer within max_wait_ms")
        } ?: 0L
        val totalWaitMs = mapped.sumOf { it.waitMs ?: 0L } + frameWait
        if (totalWaitMs > policy.maxTotalWaitMs) {
            return rejected(
                "wait total=" + totalWaitMs +
                    " exceeds max_total_wait_ms=" + policy.maxTotalWaitMs
            )
        }
        val observationId = (root["observation_id"] as? JsonPrimitive)?.takeIf { it.isString }
            ?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        return ScreenActionBatchPlan.Ready(bound, observationId)
    }

    /**
     * visit and collect are fixed compositions of primitives, so execution keeps every primitive
     * guarantee: each click is checked against the decision frame, every screenshot is retained,
     * and scrolls keep their grounding. Receipts name the source action, e.g. visit[2].back.
     */
    private fun expandHighLevel(objects: List<JsonObject>, operations: List<String>,
                                policy: ScreenActionBatchPolicy): List<Pair<String, JsonObject>> =
        objects.flatMapIndexed { index, action ->
            val label = "${operations[index]}[${index + 1}]"
            val screenshot = buildJsonObject { put("op", "screenshot") }
            when (operations[index]) {
                "visit" -> {
                    if ("target_id" !in action && ("x" !in action || "y" !in action))
                        invalid("action[$index] op=visit requires x and y or target_id")
                    listOf(
                        "$label.click" to JsonObject(action + ("op" to JsonPrimitive("click"))),
                        "$label.screenshot" to screenshot,
                        "$label.back" to buildJsonObject { put("op", "press_key"); put("key", "BACK") }
                    )
                }
                "collect" -> {
                    val pages = if ("pages" in action) action.int("pages")
                        ?: invalid("action[$index] op=collect pages must be an integer") else DEFAULT_COLLECT_PAGES
                    if (pages !in 2..policy.maxCaptureImages)
                        invalid("action[$index] op=collect pages=$pages is outside 2..${policy.maxCaptureImages}")
                    val scroll = JsonObject(action.filterKeys { it != "pages" } + ("op" to JsonPrimitive("scroll")))
                    listOf("$label.screenshot" to screenshot) + (2..pages).flatMap { _ ->
                        listOf("$label.scroll" to scroll, "$label.screenshot" to screenshot)
                    }
                }
                else -> listOf(operations[index] to action)
            }
        }

    private const val DEFAULT_COLLECT_PAGES = 3

    private fun overlaps(a: ModelCoordinateMapper.Bounds, b: ModelCoordinateMapper.Bounds): Boolean =
        !(a.right < b.left || b.right < a.left || a.bottom < b.top || b.bottom < a.top)

    /** The exact field and value let the same Agent repair a rejected batch in one step. */
    private class InvalidActionArgument(message: String) : Exception(message)

    private fun invalid(reason: String): Nothing = throw InvalidActionArgument(reason)

    private fun JsonObject.normalized(name: String): Int? {
        if (name !in this) return null
        val value = int(name) ?: invalid("$name must be an integer within 0..1000, got ${this[name]}")
        return value.takeIf { it in 0..1000 } ?: invalid("$name=$value is outside the normalized range 0..1000")
    }

    private fun JsonObject.targetPoint(): Pair<Int?, Int?> {
        if ("x" !in this && "y" !in this) return null to null
        val x = normalized("x") ?: invalid("x is required with y")
        val y = normalized("y") ?: invalid("y is required with x")
        return x to y
    }

    private fun mapAction(
        type: String,
        action: JsonObject,
        policy: ScreenActionBatchPolicy
    ): MappedScreenAction {
        return when (type) {
        "click" -> {
            val targetId = action.string("target_id")?.trim()?.takeIf(String::isNotBlank)
            val coordinateRef = action["coordinate_ref"]?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank)
                    ?: invalid("coordinate_ref must be a non-empty crop_ref string")
            }
            if (coordinateRef != null && targetId != null) invalid("coordinate_ref cannot be combined with target_id")
            val (x, y) = action.targetPoint()
            if (targetId == null && (x == null || y == null)) invalid("requires target_id or both x and y")
            MappedScreenAction(
                sourceOperation = type,
                operation = ScreenBatchOperation.CLICK,
                argumentsJson = buildJsonObject {
                    targetId?.let { put("target_id", it) }
                    coordinateRef?.let { put("coordinate_ref", it) }
                    x?.let { put("x", it) }
                    y?.let { put("y", it) }
                }.toString(),
                targetId = targetId,
                targetX = x,
                targetY = y
            )
        }
        "type_text" -> {
            val text = action.string("text") ?: invalid("text must be a string")
            val clear = (action["clear"] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.booleanOrNull
                ?: invalid("clear must be a JSON boolean")
            val method = if ("method" in action) action.string("method")?.let(TextInputMethod::parse)
                    ?: invalid("method must be one of " + TextInputMethod.entries.joinToString("|") { it.wireName })
                else TextInputMethod.SHORTCUT
            val targetId = action.string("target_id")?.trim()?.takeIf(String::isNotBlank)
            val (x, y) = action.targetPoint()
            MappedScreenAction(
                sourceOperation = type,
                operation = ScreenBatchOperation.TYPE_TEXT,
                argumentsJson = buildJsonObject {
                    targetId?.let { put("target_id", it) }
                    x?.let { put("x", it) }
                    y?.let { put("y", it) }
                    put("text", text)
                    put("clear", clear)
                    put("method", method.wireName)
                }.toString(),
                targetId = targetId,
                targetX = x,
                targetY = y
            )
        }
        "press_key" -> {
            val rawKey = action.string("key") ?: invalid("key must be a string")
            val normalizedKey = rawKey.trim().uppercase().takeIf(String::isNotBlank) ?: invalid("key cannot be blank")
            if (KeyEventPolicy.resolveKeyCode(normalizedKey) == null) invalid("key=$rawKey is not a supported Android key name")
            MappedScreenAction(type, ScreenBatchOperation.PRESS_KEY, buildJsonObject {
                put("key", normalizedKey)
            }.toString())
        }
        "scroll" -> {
            val direction = action.string("direction")?.takeIf { it in setOf("up", "down", "left", "right") }
                ?: invalid("direction must be one of up|down|left|right")
            val amount = if ("amount" in action) action.string("amount")?.takeIf { it in setOf("small", "medium", "large", "full") }
                ?: invalid("amount must be one of small|medium|large|full") else "medium"
            val speed = if ("speed" in action) action.string("speed")?.takeIf { it in setOf("slow", "normal", "fast") }
                ?: invalid("speed must be one of slow|normal|fast") else "normal"
            val region = action["region"]?.let { value ->
                val bounds = value as? JsonObject ?: invalid("region must be an object")
                if (bounds.keys != setOf("left", "top", "right", "bottom")) invalid("region requires exactly left, top, right and bottom")
                val left = bounds.normalized("left")!!
                val top = bounds.normalized("top")!!
                val right = bounds.normalized("right")!!
                val bottom = bounds.normalized("bottom")!!
                if (left >= right || top >= bottom) invalid("region requires left < right and top < bottom")
                bounds
            }
            MappedScreenAction(type, ScreenBatchOperation.SCROLL, buildJsonObject {
                put("direction", direction)
                put("amount", amount)
                put("speed", speed)
                region?.let { put("region", it) }
            }.toString())
        }
        "wait" -> {
            val waitMs = if ("ms" in action) action.long("ms") ?: invalid("ms must be an integer") else policy.defaultWaitMs
            if (waitMs !in policy.minWaitMs..policy.maxWaitMs) {
                invalid("ms=$waitMs is outside ${policy.minWaitMs}..${policy.maxWaitMs}")
            }
            MappedScreenAction(type, ScreenBatchOperation.WAIT, buildJsonObject {
                put("ms", waitMs)
            }.toString(), waitMs)
        }
        "swipe", "drag", "path" -> {
            // Region is a precondition owned by the batch, not a touch-driver argument.
            val payload = action.filterKeys { it != "op" && it != "region" }
            val region = action["region"]?.let { value ->
                val bounds = value as? JsonObject ?: invalid("region must be an object")
                if (bounds.keys != setOf("left", "top", "right", "bottom"))
                    invalid("region requires exactly left, top, right and bottom")
                ModelCoordinateMapper.Bounds(bounds.normalized("left")!!, bounds.normalized("top")!!,
                    bounds.normalized("right")!!, bounds.normalized("bottom")!!).also {
                    if (it.left >= it.right || it.top >= it.bottom)
                        invalid("region requires left < right and top < bottom")
                }
            }
            if (type == "drag" && "repeat" in action) {
                val repeat = action.int("repeat") ?: invalid("repeat must be an integer")
                if (repeat !in 1..policy.maxDragRepeat) invalid("repeat=$repeat is outside 1..${policy.maxDragRepeat}")
            }
            val duration = action["duration"]?.let { action.int("duration") ?: invalid("duration must be an integer") }
            if (type != "swipe" && duration != null && duration !in 50..10000) invalid("duration=$duration is outside 50..10000")
            if (type == "path") {
                val points = action["points"] as? JsonArray ?: invalid("points must be an array")
                if (points.size !in 2..128) invalid("points requires 2..128 entries, got ${points.size}")
                points.forEachIndexed { pointIndex, value ->
                    val point = value as? JsonObject
                    if (point == null || point.keys != setOf("x", "y")) invalid("points[$pointIndex] requires exactly x and y")
                    point.normalized("x"); point.normalized("y")
                }
            } else listOf("startX", "startY", "endX", "endY").forEach {
                action.normalized(it) ?: invalid("$it is required")
            }
            if (region != null) {
                listOf("startX", "endX").firstOrNull { checkNotNull(action.int(it)) !in region.left..region.right }?.let {
                    invalid("$it=${action.int(it)} is outside region left..right ${region.left}..${region.right}; " +
                        "drag endpoints must stay inside region")
                }
                listOf("startY", "endY").firstOrNull { checkNotNull(action.int(it)) !in region.top..region.bottom }?.let {
                    invalid("$it=${action.int(it)} is outside region top..bottom ${region.top}..${region.bottom}; " +
                        "drag endpoints must stay inside region")
                }
            }
            val operation = when (type) {
                "swipe" -> ScreenBatchOperation.SWIPE
                "drag" -> ScreenBatchOperation.DRAG
                else -> ScreenBatchOperation.PATH
            }
            MappedScreenAction(type, operation, JsonObject(payload).toString(), gestureRegion = region)
        }
        "screenshot" -> MappedScreenAction(type, ScreenBatchOperation.SCREENSHOT, "{}")
            else -> invalid("is not supported")
        }
    }

    private fun rejected(reason: String) = ScreenActionBatchPlan.Rejected(reason)

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

data class ScreenActionBatchSubActionReceipt(
    val index: Int,
    val sourceOperation: String,
    val executionStatus: ToolExecutionStatus,
    val effectUnknown: Boolean = false,
    val outputPreview: String? = null,
    val targetRef: String? = null
)

data class ScreenActionBatchCancellationReceipt(
    val completedPrefix: List<ScreenActionBatchSubActionReceipt>,
    val attempted: ScreenActionBatchSubActionReceipt?,
    val unexecutedSuffix: List<ScreenActionBatchSubActionReceipt>
)

/** Invocation-local progress survives cancellation during the final observation. */
class ScreenActionBatchProgress : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ScreenActionBatchProgress>
    var receipt: ScreenActionBatchCancellationReceipt? = null
        internal set
    var finalObservation: com.droidance.core.agent.LocalActionEvidence.ObservationLite? = null
    var lastActionEndedAtElapsedMs: Long? = null
    var finalObservationWaitSatisfied: Boolean = false

    /** Keep the delivered prefix of an in-flight repeated primitive on cancellation. */
    fun updateAttemptOutput(output: String) {
        receipt = receipt?.let { current -> current.copy(attempted = current.attempted?.copy(outputPreview = output)) }
    }

    fun attachTo(cause: Throwable) {
        val latest = receipt ?: return
        if (cause.screenActionBatchCancellationReceiptOrNull() == null) {
            cause.addSuppressed(ScreenActionBatchCancellationMarker(latest))
        }
    }
}

/** Typed, suppressed metadata carried by the original cancellation exception. */
class ScreenActionBatchCancellationMarker(
    val receipt: ScreenActionBatchCancellationReceipt
) : Exception("SCREEN_ACT_BATCH_CANCELLED")

fun ScreenActionBatchCancellationReceipt.toJsonDetails(): JsonObject = buildJsonObject {
    val total = completedPrefix.size + (if (attempted == null) 0 else 1) + unexecutedSuffix.size
    put("recovery_condition", JsonPrimitive("inspect_post_action_state_before_replanning"))
    put("completed", JsonPrimitive(completedPrefix.size))
    put("attempted", JsonPrimitive(attempted?.index ?: completedPrefix.size))
    put("total", JsonPrimitive(total))
    put("status", JsonPrimitive(ToolExecutionStatus.CANCELED.name))
    put("completed_prefix", JsonArray(completedPrefix.map(::screenActionBatchReceiptJson)))
    attempted?.let { put("attempted_action", screenActionBatchReceiptJson(it)) }
    put("unexecuted_suffix", JsonArray(unexecutedSuffix.map(::screenActionBatchReceiptJson)))
}

fun ScreenActionBatchCancellationReceipt.progressMetrics(): Map<String, Long> = mapOf(
    "screen_action_batch_calls" to 1L,
    "batch_requested_actions" to (completedPrefix.size + (if (attempted == null) 0 else 1) + unexecutedSuffix.size).toLong(),
    "batch_completed_actions" to completedPrefix.size.toLong(),
    "batch_attempted_actions" to (attempted?.index ?: completedPrefix.size).toLong(),
    "batch_unexecuted_actions" to unexecutedSuffix.size.toLong()
)

fun Throwable.screenActionBatchCancellationReceiptOrNull(): ScreenActionBatchCancellationReceipt? =
    suppressed.filterIsInstance<ScreenActionBatchCancellationMarker>().lastOrNull()?.receipt

class ScreenActionBatchExecutor(
    private val policy: ScreenActionBatchPolicy,
    private val executeAction: suspend (ScreenBatchOperation, String, MappedScreenAction) -> ToolExecutionResult
) {
    constructor(
        policy: ScreenActionBatchPolicy,
        executeAction: suspend (ScreenBatchOperation, String) -> ToolExecutionResult
    ) : this(policy, { op, payload, _ -> executeAction(op, payload) })

    suspend fun execute(argumentsJson: String): ToolExecutionResult {
        val plan = ScreenActionBatchPlanner.plan(argumentsJson, policy)
        if (plan is ScreenActionBatchPlan.Rejected) {
            return ToolProtocolResult.notExecuted(
                code = "SCREEN_ACT_BATCH_REJECTED",
                retryable = true,
                details = buildJsonObject {
                    put("reason", JsonPrimitive(plan.reason))
                    put("policy", Json.encodeToJsonElement(com.droidance.core.agent.config.ScreenActionBatchPolicy.serializer(), policy))
                }
            )
        }
        val actions = (plan as ScreenActionBatchPlan.Ready).actions
        val results = mutableListOf<String>()
        var lastActionResult: ToolExecutionResult? = null
        val completed = mutableListOf<ScreenActionBatchSubActionReceipt>()
        val progress = coroutineContext[ScreenActionBatchProgress]
        fun recordProgress(nextIndex: Int, attempted: ScreenActionBatchSubActionReceipt? = null) {
            progress?.receipt = ScreenActionBatchCancellationReceipt(
                completed.toList(), attempted,
                actions.drop(nextIndex).mapIndexed { offset, action -> notExecutedReceipt(nextIndex + offset, action) }
            )
        }
        recordProgress(0)
        for (index in actions.indices) {
            val action = actions[index]
            val previousObservation = progress?.finalObservation
            val previousWaitSatisfied = progress?.finalObservationWaitSatisfied == true
            progress?.finalObservation = null
            try {
                coroutineContext.ensureActive()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                notifyCancellation(
                    ScreenActionBatchCancellationReceipt(
                        completedPrefix = completed.toList(),
                        attempted = null,
                        unexecutedSuffix = actions.drop(index).mapIndexed { offset, actionToSkip ->
                            notExecutedReceipt(index + offset, actionToSkip)
                        }
                    ),
                    cancelled
                )
                throw cancelled
            }
            recordProgress(index + 1, attemptedReceipt(index, action, ToolExecutionStatus.CANCELED))
            val result = try {
                when (action.operation) {
                    ScreenBatchOperation.WAIT -> {
                        delay(checkNotNull(action.waitMs))
                        ToolProtocolResult.success("Wait completed: " + action.waitMs + "ms.")
                    }
                    else -> executeAction(action.operation, action.argumentsJson, action)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                notifyCancellation(
                    ScreenActionBatchCancellationReceipt(
                        completedPrefix = completed.toList(),
                        attempted = attemptedReceipt(index, action, ToolExecutionStatus.CANCELED,
                            outputPreview = progress?.receipt?.attempted?.outputPreview),
                        unexecutedSuffix = actions.drop(index + 1).mapIndexed { offset, actionToSkip ->
                            notExecutedReceipt(index + 1 + offset, actionToSkip)
                        }
                    ),
                    cancelled
                )
                throw cancelled
            } catch (error: Exception) {
                val attempted = attemptedReceipt(
                    index = index,
                    action = action,
                    status = ToolExecutionStatus.INTERRUPTED,
                    effectUnknown = true,
                    outputPreview = error::class.java.simpleName
                )
                recordProgress(index + 1, attempted)
                results += (index + 1).toString() + ":" + action.sourceOperation + "=EFFECT_UNKNOWN"
                return ToolProtocolResult.interrupted(
                    code = "SCREEN_ACT_BATCH_INTERRUPTED",
                    details = progressDetails(
                        actions = actions,
                        completed = completed,
                        attempted = attempted,
                        unexecuted = actions.drop(index + 1),
                        status = "EFFECT_UNKNOWN"
                    )
                ).withProgress(actions.size, completed.size, index + 1)
            }
            // A capacity preflight did not move the display. Keep the preceding saved
            // frame for the enclosing tool instead of encoding the same page again.
            if (result.executionStatus == ToolExecutionStatus.NOT_EXECUTED &&
                result.diagnosticCode == "BATCH_CAPTURE_CAPACITY" && progress?.finalObservation == null) {
                progress?.finalObservation = previousObservation
                progress?.finalObservationWaitSatisfied = previousWaitSatisfied
            }
            if (action.operation !in setOf(ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.WAIT)) {
                lastActionResult = result
            }
            val attempted = attemptedReceipt(index, action, result)
            recordProgress(index + 1, attempted)
            results += (index + 1).toString() + ":" + action.sourceOperation + "=" +
                resultForBatchReceipt(result.outputText)
            val status = ToolResultStatus.eventStatus(result)
            if (result.executionStatus == ToolExecutionStatus.CANCELED) {
                return ToolProtocolResult.canceled(
                    code = "SCREEN_ACT_BATCH_CANCELLED",
                    details = progressDetails(
                        actions = actions,
                        completed = completed,
                        attempted = attempted,
                        unexecuted = actions.drop(index + 1),
                        status = ToolExecutionStatus.CANCELED.name
                    )
                ).withProgress(actions.size, completed.size, index + 1)
            }
            val unknownEffect = result.executionStatus == ToolExecutionStatus.INTERRUPTED ||
                result.actionEvidence?.delivery == ActionDeliveryEvidence.UNKNOWN
            if (unknownEffect) {
                return ToolProtocolResult.interrupted(
                    code = "SCREEN_ACT_BATCH_INTERRUPTED",
                    details = progressDetails(
                        actions = actions,
                        completed = completed,
                        attempted = attempted,
                        unexecuted = actions.drop(index + 1),
                        status = "EFFECT_UNKNOWN"
                    )
                ).withProgress(actions.size, completed.size, index + 1)
            }
            // Delivery permits ordered execution, not a claim about the destination or goal.
            // The device executor publishes the final observation at the batch boundary.
            val delivered = result.executionStatus == ToolExecutionStatus.SUCCEEDED &&
                result.actionEvidence?.delivery == ActionDeliveryEvidence.ACTION_DELIVERED &&
                result.evidenceStatus != ToolEvidenceStatus.INVALID &&
                result.validation?.progressState != "CONTEXT_CHANGED"
            if (status != ToolEventStatus.SUCCEEDED && !delivered) {
                return ToolProtocolResult.failed(
                    code = result.diagnosticCode ?: "SCREEN_ACT_BATCH_INTERRUPTED",
                    retryable = com.droidance.core.agent.ToolRecovery.classify(result) == com.droidance.core.agent.ToolRecovery.OBSERVE,
                    details = progressDetails(
                        actions = actions,
                        completed = completed,
                        attempted = attempted,
                        unexecuted = actions.drop(index + 1),
                        status = status.name
                    )
                ).withProgress(actions.size, completed.size, index + 1)
            }
            completed += attempted
            recordProgress(index + 1)
            if (action.operation == ScreenBatchOperation.TYPE_TEXT &&
                result.metrics["text_input_current_editor_readback_verified"] != 1L &&
                actions.drop(index + 1).any {
                    it.operation !in setOf(ScreenBatchOperation.SCREENSHOT, ScreenBatchOperation.WAIT)
                }) {
                // Preserve the delivered input as completed. A fresh view must precede any later
                // action when the runtime could not read the requested text back from the editor.
                return ToolProtocolResult.failed("BATCH_TEXT_READBACK_REQUIRED", true, details = progressDetails(
                    actions, completed, null, actions.drop(index + 1), "TEXT_READBACK_REQUIRED"
                )).withProgress(actions.size, completed.size, index + 1)
            }
        }
        return (lastActionResult ?: ToolProtocolResult.success("")).copy(outputText =
            "[SCREEN_ACT_BATCH_COMPLETED] completed=" + actions.size + "/" + actions.size +
                "\n" + results.joinToString("\n")).withProgress(actions.size, actions.size, actions.size)
    }

    private fun ToolExecutionResult.withProgress(requested: Int, completed: Int, attempted: Int) = copy(
        metrics = metrics + mapOf("batch_requested_actions" to requested.toLong(), "batch_unexecuted_actions" to (requested - attempted).toLong(), "batch_completed_actions" to completed.toLong(), "batch_attempted_actions" to attempted.toLong())
    )

    private fun notifyCancellation(
        receipt: ScreenActionBatchCancellationReceipt,
        cause: kotlinx.coroutines.CancellationException
    ) {
        if (cause.suppressed.none { it is ScreenActionBatchCancellationMarker }) {
            cause.addSuppressed(ScreenActionBatchCancellationMarker(receipt))
        }
    }

    private fun attemptedReceipt(
        index: Int,
        action: MappedScreenAction,
        result: ToolExecutionResult,
        effectUnknown: Boolean = result.executionStatus == ToolExecutionStatus.INTERRUPTED ||
            result.actionEvidence?.delivery == ActionDeliveryEvidence.UNKNOWN
    ): ScreenActionBatchSubActionReceipt = ScreenActionBatchSubActionReceipt(
        index = index + 1,
        sourceOperation = action.sourceOperation,
        executionStatus = result.executionStatus,
        effectUnknown = effectUnknown,
        outputPreview = resultForBatchReceipt(result.outputText),
        targetRef = action.targetId ?: if (action.targetX != null && action.targetY != null) "(${action.targetX},${action.targetY})" else null
    )

    private fun attemptedReceipt(
        index: Int,
        action: MappedScreenAction,
        status: ToolExecutionStatus,
        effectUnknown: Boolean = status == ToolExecutionStatus.CANCELED,
        outputPreview: String? = null
    ): ScreenActionBatchSubActionReceipt = ScreenActionBatchSubActionReceipt(
        index = index + 1,
        sourceOperation = action.sourceOperation,
        executionStatus = status,
        effectUnknown = effectUnknown,
        outputPreview = outputPreview,
        targetRef = action.targetId ?: if (action.targetX != null && action.targetY != null) "(${action.targetX},${action.targetY})" else null
    )

    private fun notExecutedReceipt(
        offset: Int,
        action: MappedScreenAction
    ): ScreenActionBatchSubActionReceipt = ScreenActionBatchSubActionReceipt(
        index = offset + 1,
        sourceOperation = action.sourceOperation,
        executionStatus = ToolExecutionStatus.NOT_EXECUTED,
        targetRef = action.targetId ?: if (action.targetX != null && action.targetY != null) "(${action.targetX},${action.targetY})" else null
    )

    private fun progressDetails(
        actions: List<MappedScreenAction>,
        completed: List<ScreenActionBatchSubActionReceipt>,
        attempted: ScreenActionBatchSubActionReceipt?,
        unexecuted: List<MappedScreenAction>,
        status: String
    ): JsonObject = buildJsonObject {
        put("recovery_condition", JsonPrimitive("inspect_post_action_state_before_replanning"))
        put("completed", JsonPrimitive(completed.size))
        put("attempted", JsonPrimitive(attempted?.index ?: completed.size))
        put("total", JsonPrimitive(actions.size))
        put("status", JsonPrimitive(status))
        put("completed_prefix", JsonArray(completed.map(::screenActionBatchReceiptJson)))
        attempted?.let { put("attempted_action", screenActionBatchReceiptJson(it)) }
        put(
            "unexecuted_suffix",
            JsonArray(unexecuted.mapIndexed { offset, action ->
                screenActionBatchReceiptJson(
                    notExecutedReceipt((attempted?.index ?: completed.size) + offset, action)
                )
            })
        )
    }

    private fun resultForBatchReceipt(result: String): String {
        val preview = result.take(600)
        val controlReceipts = result.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> CONTROL_RECEIPT_MARKERS.any { marker -> line.startsWith(marker) } }
            .filterNot { receipt -> preview.contains(receipt) }
            .distinct()
            .toList()
        return (listOf(preview) + controlReceipts)
            .filter { line -> line.isNotBlank() }
            .joinToString("\n")
    }

    companion object {
        private val CONTROL_RECEIPT_MARKERS = listOf(
            "[INPUT_FOCUS]",
            "[TEXT_INPUT]",
            "[LOCAL_VALIDATION]"
        )
    }
}

private fun screenActionBatchReceiptJson(receipt: ScreenActionBatchSubActionReceipt): JsonObject = buildJsonObject {
    put("index", JsonPrimitive(receipt.index))
    put("op", JsonPrimitive(receipt.sourceOperation))
    put("execution_status", JsonPrimitive(receipt.executionStatus.name))
    if (receipt.effectUnknown) put("effect", JsonPrimitive("UNKNOWN"))
    receipt.targetRef?.takeIf(String::isNotBlank)?.let { put("target", JsonPrimitive(it)) }
    receipt.outputPreview?.takeIf(String::isNotBlank)?.let { put("output", JsonPrimitive(it)) }
}
