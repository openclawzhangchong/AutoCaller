package com.autocaller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面 — 配置拨号参数，启停任务
 */
class MainActivity : AppCompatActivity() {

    // UI 控件
    private lateinit var etPhoneNumber: EditText
    private lateinit var etCallCount: EditText
    private lateinit var etDuration: EditText
    private lateinit var etInterval: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatusLabel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnOpenAccessibility: Button

    private var isTaskRunning = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateStr = intent.getStringExtra(TaskAction.EXTRA_STATE)
            val msg = intent.getStringExtra(TaskAction.EXTRA_MSG) ?: ""
            val current = intent.getIntExtra(TaskAction.EXTRA_CURRENT, 0)
            val total = intent.getIntExtra(TaskAction.EXTRA_TOTAL, 0)

            runOnUiThread {
                val state = stateStr?.let { try { TaskState.valueOf(it) } catch (_: Exception) { null } }
                updateUI(state, msg, current, total)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        registerReceiver(stateReceiver, IntentFilter(TaskAction.STATE_UPDATE))
        updateUI(TaskState.IDLE, "就绪", 0, 0)
    }

    override fun onDestroy() {
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun initViews() {
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        etCallCount = findViewById(R.id.etCallCount)
        etDuration = findViewById(R.id.etDuration)
        etInterval = findViewById(R.id.etInterval)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatusLabel = findViewById(R.id.tvStatusLabel)
        tvStatus = findViewById(R.id.tvStatus)
        tvProgress = findViewById(R.id.tvProgress)
        progressBar = findViewById(R.id.progressBar)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener { onStartClick() }
        btnStop.setOnClickListener { onStopClick() }
        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "请找到「自动拨号器」并开启无障碍服务", Toast.LENGTH_LONG).show()
        }
    }

    private fun onStartClick() {
        // 检查无障碍服务
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("自动挂断功能需要开启「自动拨号器」的无障碍服务权限。\n\n" +
                        "步骤：设置 → 无障碍 → 已安装的应用 → 自动拨号器 → 开启")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 检查权限
        val needed = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return
        }

        // 读取配置
        val number = etPhoneNumber.text.toString().trim()
        if (number.isBlank()) {
            etPhoneNumber.error = "请输入电话号码"
            return
        }

        val count = (etCallCount.text.toString().toIntOrNull() ?: 1).coerceIn(1, 999)
        val duration = (etDuration.text.toString().toIntOrNull() ?: 10).coerceIn(1, 3600)
        val interval = (etInterval.text.toString().toIntOrNull() ?: 5).coerceIn(1, 300)

        val config = CallConfig(
            phoneNumber = number,
            callCount = count,
            callDurationSec = duration,
            intervalSec = interval
        )

        CallSchedulerService.start(this, config)
        isTaskRunning = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun onStopClick() {
        CallSchedulerService.stop(this)
        isTaskRunning = false
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        updateUI(TaskState.IDLE, "已停止", 0, 0)
    }

    private fun updateUI(state: TaskState?, msg: String, current: Int, total: Int) {
        tvStatus.text = msg

        when (state) {
            TaskState.IDLE -> {
                tvStatusLabel.text = "状态：空闲"
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                tvProgress.text = "0 / 0"
            }
            TaskState.RUNNING -> {
                tvStatusLabel.text = "状态：运行中"
                progressBar.isIndeterminate = false
                progressBar.max = total
                progressBar.progress = (current - 1).coerceAtLeast(0)
                tvProgress.text = "$current / $total"
            }
            TaskState.CALLING -> {
                tvStatusLabel.text = "状态：拨号中"
                progressBar.isIndeterminate = true
                tvProgress.text = "$current / $total"
            }
            TaskState.IN_CALL -> {
                tvStatusLabel.text = "通话中"
                progressBar.isIndeterminate = true
                tvProgress.text = "$current / $total"
            }
            TaskState.COMPLETED -> {
                tvStatusLabel.text = "已完成"
                progressBar.isIndeterminate = false
                progressBar.progress = progressBar.max
                tvProgress.text = "$total / $total"
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                isTaskRunning = false
            }
            TaskState.CANCELLED -> {
                tvStatusLabel.text = "已取消"
                progressBar.isIndeterminate = false
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                isTaskRunning = false
            }
            TaskState.ERROR -> {
                tvStatusLabel.text = "出错"
                progressBar.isIndeterminate = false
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                isTaskRunning = false
            }
            null -> {}
        }
    }

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onStartClick()
            } else {
                Toast.makeText(this, "需要授予权限才能拨号", Toast.LENGTH_SHORT).show()
            }
        }
    }
}