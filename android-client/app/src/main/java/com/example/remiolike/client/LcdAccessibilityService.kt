package com.example.remiolike.client

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

        fun isReady(): Boolean = instance != null

        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            return service.dispatchGestureOnMain(gesture)
        }

        fun longPress(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 650))
                .build()
            return service.dispatchGestureOnMain(gesture)
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
            return service.dispatchGestureOnMain(gesture)
        }

        fun back(): Boolean = instance?.performGlobalActionOnMain(GLOBAL_ACTION_BACK) ?: false

        fun home(): Boolean = instance?.performGlobalActionOnMain(GLOBAL_ACTION_HOME) ?: false

        fun recents(): Boolean = instance?.performGlobalActionOnMain(GLOBAL_ACTION_RECENTS) ?: false
    }

    private fun dispatchGestureOnMain(gesture: GestureDescription): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dispatchGesture(gesture, null, null)
        }

        val accepted = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            accepted.set(dispatchGesture(gesture, null, null))
            latch.countDown()
        }
        latch.await(1000, TimeUnit.MILLISECONDS)
        return accepted.get()
    }

    private fun performGlobalActionOnMain(action: Int): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return performGlobalAction(action)
        }

        val accepted = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            accepted.set(performGlobalAction(action))
            latch.countDown()
        }
        latch.await(1000, TimeUnit.MILLISECONDS)
        return accepted.get()
    }
}
