// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.droidance.R
import com.droidance.ui.mascot.MascotFraming
import com.droidance.ui.mascot.MascotMotion
import com.droidance.ui.mascot.MascotPainter
import com.droidance.ui.mascot.MascotPose
import com.droidance.ui.overlay.RuntimeIslandActivityPainter
import com.droidance.ui.overlay.RuntimeIslandAnimations
import com.droidance.ui.overlay.RuntimeIslandLabelView
import com.droidance.ui.overlay.RuntimeIslandPresentation
import com.droidance.ui.overlay.RuntimeIslandStyle
import kotlin.math.abs

/** Draws and controls the viewer island without owning an Agent or virtual-display session. */
@SuppressLint("ViewConstructor") // Programmatic view: host callbacks are required.
internal class ViewerControlIslandView(
    context: Context,
    private val onClose: () -> Unit,
    private val onBack: () -> Unit,
    private val onTextInput: () -> Unit,
    private val onToggle: () -> Unit,
    private val onStop: () -> Unit,
    private val onDragStart: (Float, Float) -> Unit,
    private val onDragMove: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit
) : LinearLayout(context) {
    private lateinit var statusView: RuntimeIslandLabelView
    private lateinit var backView: ImageButton
    private lateinit var textInputView: ImageButton
    private lateinit var controlView: ImageButton
    private lateinit var stopView: ImageButton
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var executing = false
    private val density: Float get() = resources.displayMetrics.density
    private val chipSizePx: Int get() = dp(RuntimeIslandStyle.CHIP_SIZE_DP)
    private val controlTargetPx: Int get() = dp(RuntimeIslandStyle.VIEWER_CONTROL_TARGET_DP)
    private fun dp(value: Float): Int = (value * density).toInt()

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.7f
        color = Color.parseColor(RuntimeIslandStyle.HAIRLINE)
    }
    private val pillBounds = RectF()
    private val mascotBounds = RectF()
    private val mascotPainter = MascotPainter(context)
    private val activityPainter = RuntimeIslandActivityPainter()
    private val spaceTogglePainter = RuntimeIslandStyle.SpaceTogglePainter()
    private var animationStartedAtMs = SystemClock.uptimeMillis()

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val animated = executing && RuntimeIslandAnimations.areEnabled(context)
        if (Build.VERSION.SDK_INT >= 35) {
            setRequestedFrameRate(if (animated || dragging) REQUESTED_FRAME_RATE_CATEGORY_HIGH
                else REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
        val elapsedMs = if (animated) now - animationStartedAtMs else 0L
        val radius = height / 2f
        pillBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        pillPaint.color = RuntimeIslandStyle.fillColor()
        canvas.drawRoundRect(pillBounds, radius, radius, pillPaint)
        spaceTogglePainter.draw(canvas, density, height / 2f, expanded = true)
        val mascotSize = dp(RuntimeIslandStyle.MASCOT_SIZE_DP).toFloat()
        val mascotLeft = dp(RuntimeIslandStyle.MASCOT_LEFT_DP + RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP / 2f).toFloat()
        mascotBounds.set(mascotLeft, (height - mascotSize) / 2f, mascotLeft + mascotSize, (height + mascotSize) / 2f)
        mascotPainter.draw(canvas, mascotBounds,
            MascotFraming.FULL, if (executing) MascotMotion.loopingDancePose(elapsedMs, !animated) else MascotPose())
        pillBounds.inset(hairlinePaint.strokeWidth / 2f, hairlinePaint.strokeWidth / 2f)
        canvas.drawRoundRect(pillBounds, radius, radius, hairlinePaint)

        if (animated && controlView.visibility == View.VISIBLE) {
            activityPainter.drawOrbit(canvas, controlView.left + controlView.width / 2f,
                controlView.top + controlView.height / 2f, density, elapsedMs)
        }
        if (animated && isAttachedToWindow && isShown && windowVisibility == View.VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    // Let buttons receive taps; once either axis crosses touch slop the parent owns the gesture.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            dragStartRawX = ev.rawX
            dragStartRawY = ev.rawY
            dragging = false
            onDragStart(ev.rawX, ev.rawY)
        }
        if (ev.actionMasked == MotionEvent.ACTION_MOVE &&
            (abs(ev.rawX - dragStartRawX) > touchSlop || abs(ev.rawY - dragStartRawY) > touchSlop)) {
            dragging = true
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility") // This surface only drags; its child controls expose the clickable actions.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.rawX - dragStartRawX) > touchSlop || abs(ev.rawY - dragStartRawY) > touchSlop) dragging = true
                if (dragging) onDragMove(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) onDragMove(ev.rawX, ev.rawY)
                    onDragEnd()
                }
                dragging = false
            }
        }
        return true
    }

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, dp(RuntimeIslandStyle.EDGE_PADDING_DP), 0)

        // 透明点击区覆盖收起标识和吉祥物；拖动仍由父容器在超过阈值后接管。
        addView(View(context).apply {
            contentDescription = context.getString(R.string.takeover_close_viewer)
            isFocusable = true
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(
            dp(RuntimeIslandStyle.LABEL_INSET_DP + RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP),
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // The label absorbs spare width; controls keep separate touch targets.
        addView(RuntimeIslandLabelView(context).apply {
            setLabel("", executing = false)
        }.also { statusView = it }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(
            controlIconChip(R.drawable.ic_overlay_arrow_back, R.string.common_back) {
                onBack()
            }.also {
                backView = it
                it.visibility = View.GONE
            }
        )
        addView(
            controlIconChip(R.drawable.ic_overlay_keyboard, R.string.takeover_text_input) {
                onTextInput()
            }.also {
                textInputView = it
                it.visibility = View.GONE
            }
        )
        addView(
            controlIconChip(R.drawable.ic_overlay_pause, R.string.takeover_start_control) {
                onToggle()
            }.also { controlView = it }
        )
        addView(
            controlIconChip(R.drawable.ic_overlay_stop, R.string.common_stop) {
                onStop()
            }.also {
                stopView = it
                it.setImageDrawable(RuntimeIslandStyle.stopIcon(density))
                it.imageTintList =
                    ColorStateList.valueOf(Color.parseColor(RuntimeIslandStyle.STOP_TINT))
            }
        )
    }

    private fun controlIconChip(
        drawableRes: Int,
        description: Int,
        onClick: () -> Unit
    ): ImageButton = ImageButton(context).apply {
        setImageResource(drawableRes)
        contentDescription = context.getString(description)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        // The vectors include viewport insets; leave enough room for their visible glyphs to match stop.
        val padding = (controlTargetPx - chipSizePx) / 2 + dp(2f * RuntimeIslandStyle.SCALE)
        setPadding(padding, padding, padding, padding)
        setOnClickListener { onClick() }
        background = controlBackground()
        imageTintList = ColorStateList.valueOf(Color.parseColor(RuntimeIslandStyle.LABEL))
        layoutParams = LinearLayout.LayoutParams(controlTargetPx, controlTargetPx)
    }

    private fun controlBackground(): InsetDrawable = InsetDrawable(
        RuntimeIslandStyle.chip(
            density, RuntimeIslandStyle.NEUTRAL_CHIP_FILL,
            radiusDp = RuntimeIslandStyle.CHIP_SIZE_DP / 2f
        ),
        (controlTargetPx - chipSizePx) / 2
    )

    fun render(label: String, manual: Boolean, teaching: Boolean, state: RuntimeIslandPresentation.State) {
        val wasExecuting = executing
        executing = state == RuntimeIslandPresentation.State.EXECUTING && !manual && !teaching
        if (executing && !wasExecuting) animationStartedAtMs = SystemClock.uptimeMillis()
        statusView.setLabel(label, executing)
        backView.visibility = if (manual || teaching) View.VISIBLE else View.GONE
        textInputView.visibility = if ((manual || teaching) && Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE) View.VISIBLE else View.GONE
        controlView.visibility = if (teaching) View.GONE else View.VISIBLE
        controlView.setImageResource(
            if (manual) R.drawable.ic_overlay_play else R.drawable.ic_overlay_pause
        )
        controlView.contentDescription = context.getString(
            if (manual) R.string.takeover_return_control else R.string.takeover_start_control
        )
        controlView.imageTintList = ColorStateList.valueOf(RuntimeIslandStyle.chipTint(manual))
        if (Build.VERSION.SDK_INT >= 35 && !executing) {
            setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
        invalidate()
    }

    fun markActive() {
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) invalidate()
    }
}
