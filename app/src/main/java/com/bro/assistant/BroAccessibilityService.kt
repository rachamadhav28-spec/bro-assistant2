package com.bro.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * BRO's Accessibility Service. It only does what BRO asks: press Back, take a screenshot,
 * and tap the first video in YouTube results. It does not watch or store what is on screen.
 */
class BroAccessibilityService : AccessibilityService(), AccessDriver {

    override fun onServiceConnected() {
        super.onServiceConnected()
        BroAccess.driver = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing to do. BRO acts only when a command asks for it.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (BroAccess.driver === this) BroAccess.driver = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (BroAccess.driver === this) BroAccess.driver = null
        super.onDestroy()
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    override fun screenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    override fun clickFirstVideo(): ClickResult {
        val root = rootInActiveWindow ?: return ClickResult.NOT_FOUND
        if (root.packageName?.toString() != YOUTUBE_PACKAGE) return ClickResult.WRONG_APP
        val node = findFirstVideo(root) ?: return ClickResult.NOT_FOUND
        return if (tap(node)) ClickResult.CLICKED else ClickResult.NOT_FOUND
    }

    // Depth-first search, top to bottom, so the first match is the first result on screen.
    private fun findFirstVideo(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (visited++ > MAX_NODES || depth > MAX_DEPTH) return null
            if (ResultPicker.isVideoResult(node.contentDescription?.toString())) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = walk(child, depth + 1)
                if (found != null) return found
            }
            return null
        }
        return walk(root, 0)
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var steps = 0
        while (current != null && steps < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            steps++
        }
        // Fallback: a real touch in the middle of the item.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val path = Path()
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val MAX_NODES = 3000
        private const val MAX_DEPTH = 40
    }
}
