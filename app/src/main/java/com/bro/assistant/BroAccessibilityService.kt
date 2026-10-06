package com.bro.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * BRO's Accessibility Service. It only acts when BRO asks: Back, Home, screenshot, and (Stage 12)
 * reading the visible screen, tapping, typing and scrolling for a task you gave BRO.
 * It does not watch the screen on its own and does not store anything.
 */
class BroAccessibilityService : AccessibilityService(), AccessDriver {

    // Items from the latest snapshot(). An item's id is its position in this list.
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

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
        nodes = emptyList()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (BroAccess.driver === this) BroAccess.driver = null
        nodes = emptyList()
        super.onDestroy()
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    override fun screenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    // ---------- Stage 12: reading and acting on the screen ----------

    override fun snapshot(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val items = mutableListOf<ScreenItem>()
        val kept = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited++ > MAX_NODES || depth > MAX_DEPTH || items.size >= MAX_ITEMS) return
            if (!node.isVisibleToUser) return
            val own = labelOf(node)
            val label = if (own.isEmpty() && node.isClickable) nearbyText(node) else own
            if (label.isNotEmpty() || node.isEditable || node.isScrollable) {
                items.add(
                    ScreenItem(
                        id = kept.size,
                        kind = kindOf(node),
                        label = label.take(LABEL_MAX),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable
                    )
                )
                kept.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        nodes = kept
        return ScreenSnapshot(pkg, items)
    }

    override fun clickItem(id: Int): Boolean {
        val node = nodes.getOrNull(id) ?: return false
        if (!node.refresh()) return false
        return tap(node)
    }

    override fun typeInto(id: Int, text: String): Boolean {
        val node = nodes.getOrNull(id) ?: return false
        if (node.isPassword) return false
        if (!node.refresh()) return false
        if (!node.isEditable) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun submit(id: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val node = nodes.getOrNull(id) ?: return false
        if (!node.refresh()) return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    override fun scroll(down: Boolean): Boolean {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = Rect()
        for (n in nodes) {
            if (!n.isScrollable) continue
            n.getBoundsInScreen(r)
            val area = r.width() * r.height()
            if (area > bestArea) {
                best = n
                bestArea = area
            }
        }
        val target = best ?: return false
        val action = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return target.performAction(action)
    }

    /** The words of one item: its text, else its description, else its hint. Passwords are never read. */
    private fun labelOf(node: AccessibilityNodeInfo): String {
        if (node.isPassword) return "(hidden password field)"
        val text = node.text?.toString().orEmpty().trim()
        if (text.isNotEmpty()) return text
        val desc = node.contentDescription?.toString().orEmpty().trim()
        if (desc.isNotEmpty()) return desc
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString().orEmpty().trim()
            if (hint.isNotEmpty()) return hint
        }
        return ""
    }

    /** A tappable row with no label of its own takes the words of the things inside it. */
    private fun nearbyText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()

        fun collect(n: AccessibilityNodeInfo, depth: Int) {
            if (parts.size >= 4 || depth > 4) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (c.isPassword) continue
                val t = labelOf(c)
                if (t.isNotEmpty()) parts.add(t)
                collect(c, depth + 1)
                if (parts.size >= 4) return
            }
        }

        collect(node, 0)
        return parts.joinToString(", ")
    }

    private fun kindOf(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString().orEmpty()
        return when {
            node.isEditable || cls.endsWith("EditText") -> "input"
            cls.endsWith("Button") -> "button"
            cls.endsWith("ImageView") -> "image"
            node.isClickable -> "item"
            else -> "text"
        }
    }

    // ---------- YouTube first result (Stage 11) ----------

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
        private const val MAX_ITEMS = 150
        private const val LABEL_MAX = 120
    }
}
