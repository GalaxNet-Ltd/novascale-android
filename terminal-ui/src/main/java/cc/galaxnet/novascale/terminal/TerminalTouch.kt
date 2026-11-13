// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.terminal

import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import kotlin.math.ceil

internal fun shouldRequestSoftwareKeyboard(hasExternalHardwareKeyboard: Boolean) = !hasExternalHardwareKeyboard

internal class TerminalScrollAccumulator {
    private var fractionalRows = 0f
    fun consume(pixelDelta: Float, cellHeight: Float): Int {
        if (!pixelDelta.isFinite() || !cellHeight.isFinite() || cellHeight <= 0f) return 0
        fractionalRows += pixelDelta / cellHeight
        val rows = fractionalRows.toInt()
        fractionalRows -= rows
        return rows
    }
    fun reset() { fractionalRows = 0f }
}
internal fun terminalWheelRows(axisValue: Float): Int = if (!axisValue.isFinite() || axisValue == 0f) 0
    else (-kotlin.math.sign(axisValue) * ceil(abs(axisValue)).coerceAtMost(32f) * 3).toInt()

/** Gesture state is owned by the surface and canceled at focus/mode/lifecycle boundaries. */
internal class TerminalTouch(private val view: NovaTerminalView) {
    private val rows = TerminalScrollAccumulator()
    private var held: Int? = null
    private var px = 0f
    private var py = 0f
    private var meta = 0
    private var multi = false
    private var zoom = false
    private var selectionDrag = false
    private var scale = 1f
    private var touchSize = 0f
    private val gestures = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            view.performClick()
            view.requestTerminalInput()
            if (view.mouseTracking) {
                view.mouse(0, e.x, e.y, e.metaState)
                view.mouse(0, e.x, e.y, e.metaState, release = true)
            }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { view.selectWord(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) {
            if (multi || zoom) return
            if (view.mouseTracking) press(0, e.x, e.y, e.metaState) else view.selectWord(e.x, e.y)
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom || multi || selectionDrag || held != null) return true
            view.scrollRows(rows.consume(distanceY, view.cellHeight), e2.x, e2.y, e2.metaState)
            return true
        }
    })
    private val scaling = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scale = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale *= detector.scaleFactor
            if (zoom || abs(scale - 1f) > .04f) {
                if (!zoom) { zoom = true; release() }
                view.setTextSizeSp(touchSize * scale)
            }
            return true
        }
    })

    fun cancel(sendRelease: Boolean = true) {
        if (sendRelease) release() else held = null
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel); scaling.onTouchEvent(cancel); cancel.recycle()
        multi = false; zoom = false; selectionDrag = false; rows.reset()
    }
    fun touch(event: MotionEvent): Boolean {
        px = event.x; py = event.y; meta = event.metaState
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return mousePointer(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            rows.reset(); multi = false; zoom = false; touchSize = view.fontSizeSp
            selectionDrag = view.startSelectionDrag(px, py)
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (selectionDrag) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) view.moveSelection(px, py)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                selectionDrag = false; view.endSelectionDrag()
            }
            return true
        }
        scaling.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                val cancelled = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancelled); cancelled.recycle()
                if (view.mouseTracking && !zoom) { release(); press(2, px, py, meta) }
            }
            MotionEvent.ACTION_MOVE -> if (held != null && !zoom) view.mouse(held!!, px, py, meta, motion = true)
            MotionEvent.ACTION_POINTER_UP -> release()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = held != null || multi || zoom
                release()
                if (!consumed) gestures.onTouchEvent(event)
                multi = false; zoom = false; rows.reset()
                view.parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        if (!multi && !zoom) gestures.onTouchEvent(event)
        return true
    }
    fun generic(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            view.scrollRows(terminalWheelRows(event.getAxisValue(MotionEvent.AXIS_VSCROLL)), event.x, event.y, event.metaState)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE && view.mouseTracking) {
            view.mouse(3, event.x, event.y, event.metaState, motion = true)
            return true
        }
        return false
    }
    private fun mousePointer(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.requestTerminalInput(false)
                if (view.mouseTracking) press(if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) 2
                    else if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) 1 else 0, px, py, meta)
                else { view.selectWord(px, py); selectionDrag = view.startSelectionDrag(px, py) }
            }
            MotionEvent.ACTION_MOVE -> if (held != null) view.mouse(held!!, px, py, meta, motion = true)
                else if (selectionDrag) view.moveSelection(px, py)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { release(); selectionDrag = false; view.endSelectionDrag() }
        }
        return true
    }
    private fun press(button: Int, x: Float, y: Float, state: Int) {
        held = button; px = x; py = y; meta = state
        view.mouse(button, x, y, state)
    }
    private fun release() { held?.let { view.mouse(it, px, py, meta, release = true) }; held = null }
}
