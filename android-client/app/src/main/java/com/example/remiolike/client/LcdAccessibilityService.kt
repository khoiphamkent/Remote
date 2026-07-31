package com.example.remiolike.client

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class LcdAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    companion object {
        private var instance: LcdAccessibilityService? = null

        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun longPress(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 650))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 320): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun home(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
    }
}
