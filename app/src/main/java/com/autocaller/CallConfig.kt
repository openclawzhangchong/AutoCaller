package com.autocaller

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 拨号任务配置
 */
@Parcelize
data class CallConfig(
    val phoneNumber: String = "",   // 目标号码
    val callCount: Int = 1,         // 拨打次数
    val callDurationSec: Int = 10,  // 每次通话时长（秒）
    val intervalSec: Int = 5        // 两次呼叫间隔（秒）
) : Parcelable