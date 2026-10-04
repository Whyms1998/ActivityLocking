package com.activitylock.app.core

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * 设备管理员：激活后本 App 无法被直接卸载（必须先停用管理员），
 * 堵死"卸载/清除数据"这条最大的绕过路径。附带一键锁屏能力。
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(ctx: Context, intent: Intent): CharSequence =
        "警告：停用后「应用锁」将被直接卸载，所有自律保护（锁定、限额、专注会话）立即失效。" +
            "确定要放弃本次专注计划吗？"

    override fun onDisabled(ctx: Context, intent: Intent) {
        // 停用管理员不需要特殊处理：锁定层由 Watcher 承担，继续生效
    }
}
