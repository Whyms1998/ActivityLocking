package com.activitylock.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.activitylock.app.core.AmnestyStore
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockStrength
import com.activitylock.app.core.ScheduleEngine
import com.activitylock.app.core.UnlockExemptions
import com.activitylock.app.ui.components.UnlockVerifyDialog
import com.activitylock.app.ui.screens.relockDelayLabel
import com.activitylock.app.ui.theme.ActivityLockTheme

/**
 * 「特赦」磁贴的直达验证页（v1.29.1）。
 *
 * ## 为什么不是打开设置页
 *
 * 从控制中心点磁贴的人只有一个目的：**现在要用一下**。把他丢进特赦设置页，
 * 等于让他先看一遍配额、准入、递增，再自己点「申请」—— 多两步，而且那两步
 * 恰好是"我都点进来了，干脆把次数调高一点"的入口。
 *
 * 所以磁贴在「能申请」时直接拉起本页：屏幕上只有验证卡片，没有配置项。
 * 验证通过 → 放行 + 扣一次 + 关闭，回桌面去用。
 *
 * ## 为什么不能申请时不在这里解释
 *
 * 未启用 / 次数用完 / 计划生效 —— 这三种都**不是**验证能解决的事，
 * 必须让用户看到"还剩多少、为什么不能"，所以转去特赦页，由页面说明。
 * 在这里弹一句 Toast，至少让他知道不是点空了。
 */
class AmnestyApplyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)

        val ctx = applicationContext
        val planBlocked = LockStrength.noExemptInSchedule(ctx) &&
            ScheduleEngine.anyActive(AppStore.schedules)

        if (!AmnestyStore.allowed(ctx) || planBlocked) {
            val msg = when {
                planBlocked -> "计划生效中，暂不可申请特赦"
                !AmnestyStore.enabled(ctx) -> "特赦未启用"
                else -> "本周期特赦次数已用完"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            openAmnestyPage()
            finish()
            return
        }

        // ★ 必须先取：record() 之后 used+1，durationForNext 会缩短一档，
        //   于是"弹窗上写 10 分钟、实际只能 5 分钟"。
        val req = AmnestyStore.requirementForNext(ctx)
        val sec = AmnestyStore.durationForNext(ctx)

        setContent {
            ActivityLockTheme {
                UnlockVerifyDialog(
                    requirement = req,
                    title = "申请特赦",
                    hint = "放行 " + relockDelayLabel(sec) + "，到期自动落锁",
                    onConfirmed = {
                        UnlockExemptions.addGlobal(ctx, System.currentTimeMillis() + sec * 1000L)
                        AmnestyStore.record(ctx)
                        AmsController.writeRules()
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun openAmnestyPage() {
        runCatching {
            startActivity(
                android.content.Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_AMNESTY)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
