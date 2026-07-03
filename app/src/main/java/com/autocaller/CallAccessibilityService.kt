package com.autocaller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务 — 核心功能：
 * 1. 检测电话 APP 是否进入通话界面（OFFHOOK）
 * 2. 通话时长到达后，自动挂断
 */
class CallAccessibilityService : AccessibilityService() {

    private var callConnectTime: Long = 0L
    private var targetDurationMs: Long = 0L

    fun setTargetDuration(seconds: Int) {
        targetDurationMs = seconds * 1000L
        Log.d(TAG, "Target duration set to ${seconds}s")
    }

    fun markCallConnected() {
        callConnectTime = System.nanoTime()
        Log.d(TAG, "Call connected at $callConnectTime")
    }

    fun resetCallState() {
        callConnectTime = 0L
        targetDurationMs = 0L
    }

    private fun shouldHangUp(): Boolean {
        if (callConnectTime == 0L || targetDurationMs == 0L) return false
        val elapsed = (System.nanoTime() - callConnectTime) / 1_000_000L
        Log.d(TAG, "Elapsed: ${elapsed}ms / Target: ${targetDurationMs}ms")
        return elapsed >= targetDurationMs
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: ""
                Log.d(TAG, "Window changed: $packageName")
                if (shouldHangUp() && isDialerPackage(packageName)) {
                    hangUp()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (shouldHangUp()) hangUp()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    /** 执行挂断 */
    private fun hangUp() {
        Log.i(TAG, "Attempting to hang up...")

        // 方案 A：全局挂断动作（Android 9+, 需要无障碍服务开启）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_END_CALL)
            Log.i(TAG, "GLOBAL_ACTION_END_CALL: success=$success")
            if (success) {
                callConnectTime = 0L
                return
            }
        }

        // 方案 B：在界面中找到挂断按钮并点击
        val dialerRoot = rootInActiveWindow ?: run {
            Log.w(TAG, "Root node is null")
            return
        }

        val endCallButton = findEndCallButton(dialerRoot)
        if (endCallButton != null) {
            Log.i(TAG, "Found end-call button via node tree, clicking...")
            endCallButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            endCallButton.recycle()
            callConnectTime = 0L
        } else {
            Log.w(TAG, "Could not find end-call button")
        }
    }

    private fun findEndCallButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (className.contains("ImageView") || className.contains("Button")) {
            val hints = listOf("end call", "挂断", "结束通话", "hang up", "decline")
            if (hints.any { contentDesc.contains(it) }) return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEndCallButton(child)
            if (result != null) { child.recycle(); return result }
            child.recycle()
        }
        return null
    }

    private fun isDialerPackage(pkg: String): Boolean {
        val dialers = listOf(
            "com.android.dialer", "com.android.incallui",
            "com.google.android.dialer", "com.android.phone",
            "com.huawei.incallui", "com.oneplus.dialer",
            "com.xiaomi.incall", "com.vivo.incallui",
            "com.android.server.telecom", "com.oppo.incallui"
        )
        return dialers.any { pkg.startsWith(it) }
    }

    companion object {
        private const val TAG = "CallAccService"
    }
}