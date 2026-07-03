package com.autocaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 监听电话状态变化，通知 SchedulerService。
 *
 * 配合 CallAccessibilityService 一起工作：
 *   - Receiver 检测 RINGING / IDLE（粗略状态）
 *   - AccessibilityService 检测 OFFHOOK（精确的通话中状态）
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state changed: $state number=$phoneNumber")

        // 转发给 SchedulerService 处理
        val forward = Intent(context, CallSchedulerService::class.java).apply {
            action = TelephonyManager.ACTION_PHONE_STATE_CHANGED
            putExtra(TelephonyManager.EXTRA_STATE, state)
        }
        context.startForegroundService(forward)
    }

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }
}