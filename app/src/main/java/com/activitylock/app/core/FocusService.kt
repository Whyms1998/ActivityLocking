package com.activitylock.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 专注会话倒计时服务：前台常驻显示剩余时间，到点自动结束会话。
 * 会话的拦截由 Watcher 的包级层承担，本服务只负责计时与收尾。
 */
class FocusService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val session = FocusState.load(applicationContext)
            if (session == null) {
                stopSelf()
                return
            }
            val remainingMin = ((session.endAt - System.currentTimeMillis() + 59_999) / 60_000).toInt()
            if (remainingMin <= 0) {
                FocusEngine.reconcile(applicationContext)
                stopSelf()
                return
            }
            updateNotification(remainingMin, session.durationMin)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        runCatching {
            startForeground(NOTIFY_ID, buildNotification(null))
            handler.post(tick)
        }.onFailure {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() = ensureChannel(applicationContext)

    private fun buildNotification(remainingMin: Int?): Notification {
        ensureChannel()
        val text = remainingMin?.let { "剩余 $it 分钟" } ?: "专注进行中"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("专注进行中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(remainingMin: Int, totalMin: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFY_ID, buildNotification(remainingMin))
    }

    companion object {
        /** 通知渠道名。FocusEngine 的结束提示也复用它——渠道是持久化的，
         *  即使服务已停止，只要渠道建过一次就能发通知。 */
        const val CHANNEL_ID = "focus_session"

        // ★ 通知 id 全局唯一分配表（避免两个前台服务互相顶掉通知）：
        //   QuotaService 2001/2002 · AutoFreezeService 3001 · AutoRelockService 3002
        //   · FocusService 3003 · FocusEngine 结束提示 3004
        //   旧值 3002 与 AutoRelockService 撞车：专注与自动锁闭同时守护时，
        //   后发的通知会覆盖前一个，导致某一方的前台通知"消失"。
        private const val NOTIFY_ID = 3003

        /** 幂等创建渠道，供其它组件在服务未启动时也能正常发通知。 */
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "专注会话", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, FocusService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, FocusService::class.java)) }
        }
    }
}
