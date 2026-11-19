package dev.astra.app.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * "Astra control": the accessibility service that lets the other phone tap, swipe and type
 * on this one. Android only lets an accessibility service do this, and only after you turn
 * it on yourself in Settings > Accessibility. It does nothing unless a session is live.
 *
 * Input arrives in the viewer's format (0 to 1 coordinates, see web-viewer/viewer.js):
 *   tap (left = tap, right = long press), down / move / up (a swipe or drag),
 *   scroll, text, key (backspace / enter), nav (back / home / recents / notifications / lock).
 */
class ControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var stroke: MutableList<Pair<Float, Float>>? = null
    private var strokeStart = 0L
    private var scrollAcc = 0f
    private var scrollAccX = 0f
    private var scrollPosted = false

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val m = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun px(ev: JSONObject): Pair<Float, Float> {
        val (w, h) = screen()
        val x = ev.optDouble("x", 0.5).coerceIn(0.0, 1.0)
        val y = ev.optDouble("y", 0.5).coerceIn(0.0, 1.0)
        return (x * (w - 1)).toFloat() to (y * (h - 1)).toFloat()
    }

    /** Returns an error text for the viewer, or null when the event was handled. */
    fun handle(ev: JSONObject): String? {
        when (ev.optString("type")) {
            "tap" -> {
                val (x, y) = px(ev)
                val long = ev.optString("button", "left") == "right"
                gesture(listOf(x to y), if (long) 700L else 60L)
            }
            "down" -> { stroke = mutableListOf(px(ev)); strokeStart = System.currentTimeMillis() }
            "move" -> stroke?.let { if (it.size < 400) it.add(px(ev)) }
            "up" -> {
                val pts = stroke ?: return null
                stroke = null
                pts.add(px(ev))
                // The whole swipe is replayed when the finger lifts, at the speed it was drawn.
                val ms = (System.currentTimeMillis() - strokeStart).coerceIn(80L, 4000L)
                gesture(pts, ms)
            }
            "scroll" -> {
                // Two-finger drag on the viewer. Collect a little, then swipe once.
                scrollAcc += ev.optDouble("dy", 0.0).toFloat()
                scrollAccX += ev.optDouble("dx", 0.0).toFloat()
                if (!scrollPosted) {
                    scrollPosted = true
                    main.postDelayed({ flushScroll() }, 120)
                }
            }
            "text" -> return typeText(ev.optString("text"))
            "key" -> return when (ev.optString("key")) {
                "backspace" -> editFocused { it.dropLast(1) }
                "enter" -> enter()
                "esc" -> { performGlobalAction(GLOBAL_ACTION_BACK); null }
                else -> null // arrows, tab and so on have no meaning on a touch screen
            }
            "nav" -> {
                val action = when (ev.optString("action")) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "Locking needs Android 9"
                    else -> return "Unknown button"
                }
                performGlobalAction(action)
            }
            "cad" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> return null
        }
        return null
    }

    private fun flushScroll() {
        scrollPosted = false
        val (w, h) = screen()
        val dy = (scrollAcc * h * 0.06f).coerceIn(-h * 0.45f, h * 0.45f)
        val dx = (-scrollAccX * w * 0.06f).coerceIn(-w * 0.45f, w * 0.45f)
        scrollAcc = 0f; scrollAccX = 0f
        if (kotlin.math.abs(dy) < 4 && kotlin.math.abs(dx) < 4) return
        val cx = w / 2f; val cy = h / 2f
        gesture(listOf(cx - dx / 2 to cy - dy / 2, cx + dx / 2 to cy + dy / 2), 250L)
    }

    private fun gesture(points: List<Pair<Float, Float>>, durationMs: Long) {
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            if (points.size == 1) lineTo(points[0].first + 0.5f, points[0].second + 0.5f) // a tap needs a tiny path on some phones
            else points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtMost(GestureDescription.getMaxGestureDuration())))
            .build()
        main.post { dispatchGesture(g, null, null) }
    }

    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun editFocused(change: (String) -> String): String? {
        val node = focusedField() ?: return "Tap a text box on the phone first"
        val current = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val next = change(current)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return null
    }

    private fun typeText(text: String): String? = if (text.isEmpty()) null else editFocused { it + text }

    private fun enter(): String? {
        val node = focusedField() ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return null
        }
        return editFocused { it + "\n" }
    }

    companion object {
        @Volatile var instance: ControlService? = null
            private set

        /** True when "Astra control" is switched on in Settings > Accessibility. */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val me = ComponentName(context, ControlService::class.java).flattenToString()
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
