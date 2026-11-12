// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.*

/** One primitive shape for a screen action group of any length. */
object ScreenActionBatchProtocol {
    val actionFields = linkedMapOf(
        "click" to setOf("op", "x", "y", "target_id", "coordinate_ref"),
        "type_text" to setOf("op", "text", "clear", "method", "x", "y", "target_id"),
        "press_key" to setOf("op", "key"),
        "scroll" to setOf("op", "direction", "amount", "speed", "region"),
        "swipe" to setOf("op", "startX", "startY", "endX", "endY", "duration"),
        "drag" to setOf("op", "startX", "startY", "endX", "endY", "duration", "repeat", "region"),
        "path" to setOf("op", "points", "duration"),
        "wait" to setOf("op", "ms"),
        "screenshot" to setOf("op"),
        // High-level actions that the planner expands into the primitives above.
        "visit" to setOf("op", "x", "y", "target_id"),
        "collect" to setOf("op", "direction", "amount", "region", "pages")
    )
    val operations: List<String> get() = actionFields.keys.toList()

    fun parameters(clearDescription: String) = linkedMapOf(
        "op" to ToolParameter("string", "Screen operation. visit opens the target, captures it and returns with BACK. collect captures the current view, then scrolls and captures for each further page.", true, operations),
        "target_id" to ToolParameter("string", "Exact target ID from the current observation. Never invent an ID; otherwise use visible x/y coordinates.", false),
        "coordinate_ref" to ToolParameter("string", "For click only: exact crop_ref from android_read(crop). x/y then use that crop's normalized 0..1000 space. Runtime maps to the original and rejects a stale source. Omit for current full-screen coordinates; cannot combine with target_id.", false),
        "x" to ToolParameter("integer", "Normalized target X, 0..1000; pair with y.", false),
        "y" to ToolParameter("integer", "Normalized target Y, 0..1000; pair with x.", false),
        "text" to ToolParameter("string", "Exact input text.", false, requiredWhen = mapOf("op" to listOf("type_text"))),
        "clear" to ToolParameter("boolean", clearDescription, false, requiredWhen = mapOf("op" to listOf("type_text"))),
        "method" to ToolParameter("string", "Display-scoped text delivery: shortcut (default) uses Ctrl+V; paste_key uses Android's Paste key. Neither switches the user's system keyboard. Injected is not verified delivery; inspect the resulting editor before retrying.", false, listOf(TextInputMethod.SHORTCUT.wireName, TextInputMethod.PASTE_KEY.wireName)),
        "key" to ToolParameter("string", "One Android KeyEvent name, such as ENTER, BACK, DEL, KEYCODE_5 or NUMPAD_ADD; numeric key codes are rejected.", false, requiredWhen = mapOf("op" to listOf("press_key"))),
        "direction" to ToolParameter("string", "Viewport direction: down reveals lower content (finger upward); up reveals higher content.", false, listOf("up", "down", "left", "right"), requiredWhen = mapOf("op" to listOf("scroll", "collect"))),
        "amount" to ToolParameter("string", "Scroll distance; defaults to medium.", false, listOf("small", "medium", "large", "full")),
        "speed" to ToolParameter("string", "Scroll speed; defaults to normal.", false, listOf("slow", "normal", "fast")),
        "region" to ToolParameter("object", "Normalized bounds of an observed control or pane. For drag, enclose the whole control and both endpoints; independent non-overlapping regions can share a bounded drag-only batch. Runtime requires unchanged structured pixels in each region and the same foreground window. Omit for normal full-page scrolling.", false,
            properties = listOf("left", "top", "right", "bottom").associateWith {
                ToolParameter("integer", "Normalized bound, 0..1000.", true)
            }, additionalProperties = false),
        "pages" to ToolParameter("integer", "Views op=collect captures, 2 to the capture limit; defaults to 3.", false),
        "ms" to ToolParameter("integer", "Bounded duration for op=wait; defaults to policy default_wait_ms.", false),
        "startX" to ToolParameter("integer", "Normalized gesture start X, 0..1000.", false, requiredWhen = mapOf("op" to listOf("swipe", "drag"))),
        "startY" to ToolParameter("integer", "Normalized gesture start Y, 0..1000.", false, requiredWhen = mapOf("op" to listOf("swipe", "drag"))),
        "endX" to ToolParameter("integer", "Normalized gesture end X, 0..1000.", false, requiredWhen = mapOf("op" to listOf("swipe", "drag"))),
        "endY" to ToolParameter("integer", "Normalized gesture end Y, 0..1000.", false, requiredWhen = mapOf("op" to listOf("swipe", "drag"))),
        "duration" to ToolParameter("integer", "Gesture duration in milliseconds.", false),
        "repeat" to ToolParameter("integer", "Identical drags, default 1, bounded by policy max_drag_repeat. Receipts count pixel changes and delivery, not recognized or verified values.", false),
        "points" to ToolParameter("array", "2..128 ordered normalized points in one continuous touch.", false,
            items = ToolParameter("object", "Path point.", properties = linkedMapOf(
                "x" to ToolParameter("integer", "Normalized X, 0..1000."),
                "y" to ToolParameter("integer", "Normalized Y, 0..1000.")
            ), additionalProperties = false), requiredWhen = mapOf("op" to listOf("path")))
    ).mapValues { (name, field) ->
        if (name == "op") field else field.copy(allowedWhen = mapOf(
            "op" to actionFields.filterValues { name in it }.keys.toList()
        ))
    }

    /** The same primitive identity is used by execution, history and presentation. */
    fun primitive(action: JsonObject): JsonObject? =
        action.takeIf { (it["op"] as? JsonPrimitive)?.contentOrNull in actionFields }
}
