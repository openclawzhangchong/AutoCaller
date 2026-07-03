package com.autocaller

/**
 * 任务状态（通过广播在 Service ↔ Activity 之间传递）
 */
object TaskAction {
    const val STATE_UPDATE = "com.autocaller.STATE_UPDATE"

    const val EXTRA_STATE = "task_state"
    const val EXTRA_MSG = "task_msg"
    const val EXTRA_CURRENT = "current_call"
    const val EXTRA_TOTAL = "total_calls"
}

enum class TaskState {
    IDLE,           // 空闲
    RUNNING,        // 运行中
    CALLING,        // 正在拨号
    IN_CALL,        // 通话中
    COMPLETED,      // 已完成
    CANCELLED,      // 已取消
    ERROR           // 错误
}