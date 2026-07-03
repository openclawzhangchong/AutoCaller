package com.autocaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autocaller.TaskAction.Companion.EXTRA_CURRENT
import com.autocaller.TaskAction.Companion.EXTRA_MSG
import com.autocaller.TaskAction.Companion.EXTRA_STATE
import com.autocaller.TaskAction.Companion.EXTRA_TOTAL
import kotlinx.coroutines.*

/**
 * 前台服务 — 调度拨号任务。
 *
 * 核心流程（协程驱动）：
 *   repeat(callCount) {
 *     1. 发起呼叫
 *     2. 无障碍服务监测通话状态，到期自动挂断
 *     3. 等待电话状态变为 IDLE
 *     4. 等待 intervalSec 后继续下一轮
 *   }
 */
class CallSchedulerService : Service() {

    private lateinit var config: CallConfig
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var currentState = TaskState.IDLE
        private set
    @Volatile var currentCallIndex = 0
        private set

    companion object {
        var accessibilityService: CallAccessibilityService? = null
        private const val TAG = "CallSchedulerSvc"
        const val CHANNEL_ID = "call_scheduler_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context, config: CallConfig) {
            val intent = Intent(context, CallSchedulerService::class.java).apply {
                putExtra("config", config)
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallSchedulerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerPhoneStateReceiver()
        registerReceiver(stateUpdateReceiver, IntentFilter(TaskAction.STATE_UPDATE))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                config = intent.getParcelableExtra("config") ?: return START_NOT_STICKY

                if (config.phoneNumber.isBlank()) {
                    sendUpdate(TaskState.ERROR, "请输入电话号码")
                    return START_NOT_STICKY
                }
                if (config.callCount <= 0 || config.callDurationSec <= 0) {
                    sendUpdate(TaskState.ERROR, "次数和时长必须大于 0")
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification("正在准备..."))
                startTask()
            }

            ACTION_STOP -> {
                cancelTask()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelTask()
        scope.cancel()
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(stateUpdateReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── 核心调度 ──────────────────────────────────────────────

    private fun startTask() {
        currentCallIndex = 0
        currentState = TaskState.RUNNING
        sendUpdate(TaskState.RUNNING, "任务启动")

        job = scope.launch {
            try {
                for (i in 1..config.callCount) {
                    if (!isActive) break
                    currentCallIndex = i
                    currentState = TaskState.CALLING

                    mainHandler.post {
                        sendUpdate(TaskState.CALLING, "第 $i/${config.callCount} 次呼叫")
                        updateNotification("第 $i/${config.callCount} 次呼叫")
                        // 配置无障碍服务的挂断计时器
                        accessibilityService?.setTargetDuration(config.callDurationSec)
                        // 拨号
                        makeCall(config.phoneNumber)
                    }

                    // 等待挂断（阻塞，由 phoneStateReceiver 报告 IDLE 解除）
                    waitForCallEnd()

                    // 间隔等待
                    if (i < config.callCount) {
                        mainHandler.post {
                            val msg = "等待 ${config.intervalSec} 秒后下一次呼叫"
                            sendUpdate(TaskState.RUNNING, msg)
                            updateNotification(msg)
                        }
                        delay(config.intervalSec * 1000L)
                    }
                }

                if (isActive) {
                    currentState = TaskState.COMPLETED
                    mainHandler.post {
                        sendUpdate(TaskState.COMPLETED, "全部完成！共拨打 ${config.callCount} 次")
                        updateNotification("任务完成 ✅")
                    }
                }
            } catch (e: CancellationException) {
                currentState = TaskState.CANCELLED
                mainHandler.post { sendUpdate(TaskState.CANCELLED, "任务已取消") }
            } catch (e: Exception) {
                Log.e(TAG, "Task error", e)
                currentState = TaskState.ERROR
                mainHandler.post { sendUpdate(TaskState.ERROR, "错误: ${e.message}") }
            } finally {
                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun cancelTask() {
        job?.cancel()
        currentState = TaskState.CANCELLED
        sendUpdate(TaskState.CANCELLED, "任务已取消")
    }

    /** 拨号 */
    private fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make call", e)
        }
    }

    /** 阻塞等待通话结束 */
    private suspend fun waitForCallEnd() {
        val deferred = CompletableDeferred<Unit>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    deferred.complete(Unit)
                }
            }
        }

        registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))

        try {
            withTimeout((config.callDurationSec + 60) * 1000L) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Wait for call end timed out")
        } finally {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    // ── 通知 ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "自动拨号",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示自动拨号任务状态"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("自动拨号器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ── 状态广播 ──────────────────────────────────────────────

    private fun sendUpdate(state: TaskState, msg: String) {
        val intent = Intent(TaskAction.STATE_UPDATE).apply {
            putExtra(EXTRA_STATE, state.name)
            putExtra(EXTRA_MSG, msg)
            putExtra(EXTRA_CURRENT, currentCallIndex)
            putExtra(EXTRA_TOTAL, config.callCount)
        }
        sendBroadcast(intent)
    }

    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 空实现：用于接收其他组件的广播
        }
    }

    // ── 电话状态广播接收器 ──────────────────────────────────

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // 通话接通 → 通知无障碍服务记录时间
                    accessibilityService?.markCallConnected()
                    mainHandler.post {
                        sendUpdate(TaskState.IN_CALL, "通话中... (${config.callDurationSec}秒后自动挂断)")
                        updateNotification("通话进行中")
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    mainHandler.post {
                        sendUpdate(TaskState.RUNNING, "通话已结束")
                    }
                }
            }
        }
    }

    private fun registerPhoneStateReceiver() {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registerReceiver(phoneStateReceiver, filter)
    }

    // ── Action 常量 ───────────────────────────────────────────

    private companion object {
        const val ACTION_START = "com.autocaller.ACTION_START"
        const val ACTION_STOP = "com.autocaller.ACTION_STOP"
    }
}