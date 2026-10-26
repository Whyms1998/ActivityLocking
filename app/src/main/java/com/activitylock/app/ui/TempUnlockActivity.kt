package com.activitylock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.activitylock.app.MainActivity
import com.activitylock.app.core.AppStore

/**
 * 旧「临时解锁」入口（控制中心磁贴拉起）。
 *
 * ★ v1.29.0：特赦通行证取代了「临时解锁」，本页退化为**纯转发器** ——
 *   不再自己跑一遍验证，直接打开主页里的 `AmnestyScreen`。
 *
 * 为什么要这么改（而不是两套界面并存）：
 * 验证页一旦有两份，配额就必然有两套计数，用户从磁贴进就能绕过设置页的配额。
 * 这是「假开关」的另一副面孔 —— 看着有上限，实际有两条路。
 *
 * 为什么保留这个类：磁贴的 PendingIntent 与旧快捷方式仍指向它；
 * 删掉会让已添加到控制中心的磁贴变成死块（点了没反应）。
 */
class TempUnlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_AMNESTY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
