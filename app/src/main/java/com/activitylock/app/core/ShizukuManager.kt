package com.activitylock.app.core

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.system.Os
import rikka.shizuku.Shizuku
import java.io.FileInputStream

/**
 * Shizuku 封装：通过 IShizukuService.newProcess 以 shell 身份执行命令，
 * 免 Root 禁用/启用任意组件。
 */
object ShizukuManager {

    data class CmdResult(val code: Int, val output: String)

    fun installed(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        installed() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        if (installed()) Shizuku.requestPermission(0)
    }

    fun run(vararg args: String): CmdResult {
        val binder = try {
            Shizuku.getBinder()
        } catch (t: Throwable) {
            null
        }
        if (binder == null || !binder.pingBinder()) {
            return CmdResult(-1, "Shizuku 服务未连接，请确认 Shizuku 正在运行")
        }
        if (!granted()) {
            return CmdResult(-1, "尚未获得 Shizuku 授权，请在设置页申请")
        }
        var process: moe.shizuku.server.IRemoteProcess? = null
        return try {
            val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
            val p = svc.newProcess(arrayOf(*args), null, null)
            process = p
            val out = FileInputStream(p.inputStream.fileDescriptor).bufferedReader().use { it.readText() }
            val err = FileInputStream(p.errorStream.fileDescriptor).bufferedReader().use { it.readText() }
            val code = p.waitFor()
            CmdResult(code, (out + "\n" + err).trim())
        } catch (t: Throwable) {
            CmdResult(-1, "执行失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { process?.destroy() }
        }
    }

    private fun failed(r: CmdResult) = r.code != 0 || r.output.contains("Exception", ignoreCase = true)

    /**
     * 依次尝试多种命令形式。注意：pm suspend/unsuspend 只支持整包、不支持组件，绝不能加入此链。
     */
    fun disable(pkg: String, cls: String): CmdResult {
        val comp = "$pkg/$cls"
        val attempts = listOf(
            "disable-user --user 0" to arrayOf("pm", "disable-user", "--user", "0", comp),
            "disable-user" to arrayOf("pm", "disable-user", comp),
            "disable --user 0" to arrayOf("pm", "disable", "--user", "0", comp),
            "disable" to arrayOf("pm", "disable", comp),
        )
        val log = StringBuilder()
        for ((label, cmd) in attempts) {
            val r = run(*cmd)
            if (!failed(r)) {
                return r.copy(output = "[pm $label] ${r.output}")
            }
            log.appendLine(">>> pm $label")
            log.appendLine("code=${r.code}")
            log.appendLine(r.output.ifBlank { "(无输出)" })
            log.appendLine()
        }
        var report = log.toString()
        runCatching {
            val miui = run("getprop", "ro.miui.ui.version.name").output.trim()
            if (miui.isNotBlank()) report += "系统: MIUI $miui\n"
            if (report.contains("SecurityException")) {
                report += "\n提示: SecurityException 通常意味着 MIUI 未开启「开发者选项 → USB 调试（安全设置）」。\n"
            }
        }
        return CmdResult(1, "所有禁用命令均失败\n$report")
    }

    fun enable(pkg: String, cls: String): CmdResult {
        val comp = "$pkg/$cls"
        val attempts = listOf(
            "enable --user 0" to arrayOf("pm", "enable", "--user", "0", comp),
            "enable" to arrayOf("pm", "enable", comp),
        )
        val log = StringBuilder()
        for ((label, cmd) in attempts) {
            val r = run(*cmd)
            if (!failed(r)) {
                return r.copy(output = "[pm $label] ${r.output}")
            }
            log.appendLine(">>> pm $label")
            log.appendLine("code=${r.code}")
            log.appendLine(r.output.ifBlank { "(无输出)" })
            log.appendLine()
        }
        return CmdResult(1, "所有启用命令均失败\n$log")
    }
}
