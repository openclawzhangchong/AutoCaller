package com.autocaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autocaller.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

/**
 * 主界面 — 配置拨号参数，启停任务。
 *
 * 权限要求（运行时动态申请）：
 *   1. CALL_PHONE — 拨号
 *   2. READ_PHONE_STATE — 监听电话状态
 *   3. POST_NOTIFICATIONS — 前台服务通知（Android 13+）
 *   4. MANAGE_OVERLAY_PERMISSION — 可选，用于显示悬浮窗
 *
 * 核心提示：用户必须手动开启「无障碍服务」才能自动挂断。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isTaskRunning = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(TaskAction.EXTRA_STATE)?.let { TaskState.valueOf(it) }
            val msg = intent.getStringExtra(TaskAction.EXTRA_MSG) ?: ""
            val current = intent.getIntExtra(TaskAction.EXTRA_CURRENT, 0)
            val total = intent.getIntExtra(TaskAction.EXTRA_TOTAL, 0)

            runOnUiThread {
                updateUI(state, msg, current, total)
            }
        }
    }

    // ── 权限请求 ──────────────────────────────────────────────

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            Snackbar.make(binding.root, "权限已就绪", Snackbar.LENGTH_SHORT).show()
        } else {
            showPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        registerReceiver(stateReceiver, IntentFilter(TaskAction.STATE_UPDATE))
    }

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        super.onDestroy()
    }

    // ── UI 初始化 ─────────────────────────────────────────────

    private fun setupUI() {
        binding.btnStart.setOnClickListener { onStartClick() }
        binding.btnStop.setOnClickListener { onStopClick() }
        binding.tvHelpAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        updateUI(TaskState.IDLE, "就绪", 0, 0)
    }

    // ── 操作 ──────────────────────────────────────────────────

    private fun onStartClick() {
        // 1. 检查无障碍服务
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("自动挂断功能需要开启「自动拨号器」的无障碍服务权限。\n\n" +
                        "步骤：设置 → 无障碍 → 已安装的应用 → 自动拨号器 → 开启")
                .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 2. 检查必要权限
        val neededPermissions = mutableListOf(
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
            return
        }

        // 3. 读取配置并启动
        val number = binding.etPhoneNumber.text.toString().trim()
        if (number.isBlank()) {
            binding.etPhoneNumber.error = "请输入电话号码"
            return
        }

        val count = (binding.etCallCount.text.toString().toIntOrNull() ?: 1).coerceIn(1, 999)
        val duration = (binding.etDuration.text.toString().toIntOrNull() ?: 10).coerceIn(1, 3600)
        val interval = (binding.etInterval.text.toString().toIntOrNull() ?: 5).coerceIn(1, 300)

        val config = CallConfig(
            phoneNumber = number,
            callCount = count,
            callDurationSec = duration,
            intervalSec = interval
        )

        CallSchedulerService.start(this, config)
        isTaskRunning = true
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
    }

    private fun onStopClick() {
        CallSchedulerService.stop(this)
        isTaskRunning = false
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        updateUI(TaskState.IDLE, "已停止", 0, 0)
    }

    // ── UI 更新 ──────────────────────────────────────────────

    private fun updateUI(state: TaskState?, msg: String, current: Int, total: Int) {
        binding.tvStatus.text = msg

        when (state) {
            TaskState.IDLE -> {
                binding.tvStatusLabel.text = "状态：空闲"
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 0
                binding.tvProgress.text = "0 / 0"
            }
            TaskState.RUNNING -> {
                binding.tvStatusLabel.text = "状态：运行中"
                binding.progressBar.isIndeterminate = false
                binding.progressBar.max = total
                binding.progressBar.progress = current - 1
                binding.tvProgress.text = "$current / $total"
            }
            TaskState.CALLING -> {
                binding.tvStatusLabel.text = "状态：拨号中"
                binding.progressBar.isIndeterminate = true
                binding.tvProgress.text = "$current / $total"
            }
            TaskState.IN_CALL -> {
                binding.tvStatusLabel.text = "通话中"
                binding.progressBar.isIndeterminate = true
                binding.tvProgress.text = "$current / $total"
            }
            TaskState.COMPLETED -> {
                binding.tvStatusLabel.text = "✅ 已完成"
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = binding.progressBar.max
                binding.tvProgress.text = "$total / $total"
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                isTaskRunning = false
            }
            TaskState.CANCELLED -> {
                binding.tvStatusLabel.text = "⛔ 已取消"
                binding.progressBar.isIndeterminate = false
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                isTaskRunning = false
            }
            TaskState.ERROR -> {
                binding.tvStatusLabel.text = "❌ 出错"
                binding.progressBar.isIndeterminate = false
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                isTaskRunning = false
            }
            null -> {}
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────

    /** 检查无障碍服务是否已开启 */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/.CallAccessibilityService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':').any { it.equals(service, ignoreCase = true) }
        } catch (_: Exception) {
            return false
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        Toast.makeText(this, "请找到「自动拨号器」并开启无障碍服务", Toast.LENGTH_LONG).show()
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("自动拨号需要「拨打电话」和「读取电话状态」权限")
            .setPositiveButton("重试") { _, _ -> onStartClick() }
            .setNegativeButton("取消", null)
            .show()
    }
}