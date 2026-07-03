package com.autocaller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_END_CALL
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
 *
 * 挂断方式（按优先级）：
 *   A. GLOBAL_ACTION_END_CALL（Android 9+, 最优雅）
 *   B. 模拟点击红色挂断按钮（兜底方案）
 */
class CallAccessibilityService : AccessibilityService() {

    private var callConnectTime: Long = 0L   // 通话建立时刻 (System.nanoTime)
    private var targetDurationMs: Long = 0L  // 目标通话时长（毫秒）

    /** 设置通话时长，由 SchedulerService 调用 */
    fun setTargetDuration(seconds: Int) {
        targetDurationMs = seconds * 1000L
        Log.d(TAG, "Target duration set to ${seconds}s")
    }

    /** 标记通话开始时间 */
    fun markCallConnected() {
        callConnectTime = System.nanoTime()
        Log.d(TAG, "Call connected at ${callConnectTime}")
    }

    /** 重置状态 */
    fun resetCallState() {
        callConnectTime = 0L
        targetDurationMs = 0L
    }

    /** 检查是否到达挂断时间 */
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
                // 电话 / 拨号器 APP 的窗口出现
                if (shouldHangUp() && isDialerPackage(packageName)) {
                    hangUp()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 内容变化时也检查一下（通话时长界面会持续更新）
                if (shouldHangUp()) {
                    hangUp()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    /** 执行挂断 */
    private fun hangUp() {
        Log.i(TAG, "Attempting to hang up...")

        // 方案 A：全局挂断动作（Android 9+, 需要无障碍服务开启）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val success = performGlobalAction(GLOBAL_ACTION_END_CALL)
            Log.i(TAG, "GLOBAL_ACTION_END_CALL: success=$success")
            if (success) {
                callConnectTime = 0L
                return
            }
        }

        // 方案 B：在界面中找到红色挂断按钮并点击
        val dialerRoot = rootInActiveWindow ?: run {
            Log.w(TAG, "Root node is null, cannot find end-call button")
            return
        }

        val endCallButton = findEndCallButton(dialerRoot)
        if (endCallButton != null) {
            Log.i(TAG, "Found end-call button via node tree, clicking...")
            endCallButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            endCallButton.recycle()
            callConnectTime = 0L
        } else {
            Log.w(TAG, "Could not find end-call button, trying coordinate click...")

            // 方案 C：在屏幕底部中央模拟点击
            val disp = display
            if (disp != null) {
                val size = android.graphics.Point()
                disp.getRealSize(size)
                val x = size.x / 2f
                val y = size.y * 0.85f

                Log.i(TAG, "Tapping at ($x, $y)")
                tapAt(x, y)
                callConnectTime = 0L
            }
        }
    }

    /** 递归查找挂断按钮 */
    private fun findEndCallButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 常见的挂断按钮特征
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (className.contains("ImageView") || className.contains("Button")) {
            val descHints = listOf("end call", "挂断", "结束通话", "结束呼叫", "hang up", "decline")
            if (descHints.any { contentDesc.contains(it) }) {
                return node
            }
        }

        // 看子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEndCallButton(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }

        return null
    }

    /** 模拟点击屏幕坐标 */
    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** 判断包名是否为电话/拨号器 */
    private fun isDialerPackage(pkg: String): Boolean {
        val dialers = listOf(
            "com.android.dialer",            // AOSP / Google
            "com.android.incallui",          // AOSP InCallUI
            "com.google.android.dialer",     // Google Phone
            "com.android.phone",             // 系统电话
            "com.huawei.incallui",           // 华为
            "com.oneplus.dialer",            // 一加
            "com.xiaomi.incall",             // 小米
            "com.vivo.incallui",             // vivo
            "com.android.server.telecom",    // Telecom
            "com.oppo.incallui"              // OPPO
        )
        return dialers.any { pkg.startsWith(it) }
    }

    companion object {
        private const val TAG = "CallAccService"
    }
}