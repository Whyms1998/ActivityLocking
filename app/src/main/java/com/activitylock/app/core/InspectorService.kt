package com.activitylock.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * Activity 检视器：无障碍服务监听窗口切换，用悬浮条实时显示
 * 当前前台界面的 包名/类名。点击悬浮条复制，可拖动位置。
 */
class InspectorService : AccessibilityService() {

    private var overlay: TextView? = null
    private var labelViewRef: TextView? = null
    private var added = false
    private var current: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName) return
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg)
        current = "$pkg/$cls"
        val line1 = "$appLabel · $pkg"
        mainHandler.post {
            labelViewRef?.text = line1
            overlay?.text = cls
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching {
            if (added) getSystemService(WindowManager::class.java)?.removeView(overlay)
        }
        added = false
        overlay = null
        labelViewRef = null
        super.onDestroy()
    }

    private fun showOverlay() {
        if (added) return
        val wm = getSystemService(WindowManager::class.java) ?: return

        val labelView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFB9C0FF.toInt())
            setSingleLine(true)
        }
        val classView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xCC1A1C23.toInt())
            setPadding(28, 16, 28, 16)
            addView(labelView)
            addView(classView)
        }

        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        box.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!moved && dx * dx + dy * dy > 100) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ev.action == MotionEvent.ACTION_UP && !moved && current.isNotBlank()) {
                        val full = current.replace("\n", "/")
                        AppStore.copyText(full)
                        AppStore.toast("已复制：$full")
                    }
                    true
                }
                else -> true
            }
        }

        runCatching {
            wm.addView(box, lp)
            overlay = classView
            labelViewRef = labelView
            added = true
        }
    }

    companion object {
        /** 供外部直接拉起本服务（无障碍开启后系统自动绑定）。 */
        fun startIntent(context: android.content.Context): Intent =
            Intent(context, InspectorService::class.java)
    }
}
