package com.coin11.taojinbi.shizuku

import android.content.pm.PackageManager
import com.coin11.taojinbi.capability.CapabilityState
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.Executors

object ShizukuBridge {

    const val REQUEST_CODE = 4107

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ShizukuCapabilityLab").apply {
            isDaemon = true
        }
    }

    fun statusText(): String {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return "binder：未连接"
        }

        val permission = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)

        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        val context = runCatching { Shizuku.getSELinuxContext() }.getOrNull()

        return buildString {
            appendLine("binder：已连接")
            appendLine("permission：${if (permission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}")
            appendLine("uid：$uid ${when (uid) { 0 -> "(root)"; 2000 -> "(shell)"; else -> "" }}")
            appendLine("server api：$version")
            append("SELinux：${context ?: "(unknown)"}")
        }
    }

    fun requestPermission(): Boolean {
        val binderReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderReady) {
            CapabilityState.publish("Shizuku 权限", "Shizuku binder 未连接。请先确认 Shizuku 正在运行。")
            return false
        }

        val permission = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)

        if (permission == PackageManager.PERMISSION_GRANTED) {
            CapabilityState.publish("Shizuku 权限", "已经授权，无需再次请求。")
            return true
        }

        val deniedPermanently = runCatching {
            Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)

        if (deniedPermanently) {
            CapabilityState.publish("Shizuku 权限", "当前状态不允许再次弹出授权请求，请在 Shizuku 中检查授权。")
            return false
        }

        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.onFailure {
            CapabilityState.publish("Shizuku 权限请求失败", it.stackTraceToString())
        }.getOrDefault(false)
    }

    fun runProbeSuite() {
        runCommand(
            label = "Shizuku 基础检查",
            command = """
                echo '=== id ==='
                id
                echo
                echo '=== users ==='
                pm list users
                echo
                echo '=== taobao user 999 ==='
                pm list packages --user 999 com.taobao.taobao
                echo
                echo '=== foreground ==='
                dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 4
            """.trimIndent(),
        )
    }

    fun forceStopTaobao(userId: Int) {
        runCommand(
            label = "force-stop 淘宝 user $userId",
            command = "am force-stop --user $userId com.taobao.taobao",
        )
    }

    fun openCoinAsUser(userId: Int, url: String) {
        runCommand(
            label = "启动淘金币 user $userId",
            command = "am start --user $userId -a android.intent.action.VIEW -d ${shQuote(url)} -p com.taobao.taobao",
        )
    }

    fun queryForegroundActivity() {
        runCommand(
            label = "前台 Activity",
            command = "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 6",
        )
    }

    private fun runCommand(label: String, command: String) {
        val ready = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!ready) {
            CapabilityState.publish(label, "Shizuku 未连接或未授权。")
            return
        }

        executor.execute {
            val result = runCatching {
                runShellReflective(command)
            }

            result.onSuccess { shell ->
                CapabilityState.publish(
                    label,
                    buildString {
                        appendLine("exitCode：${shell.exitCode}")
                        appendLine("--- stdout ---")
                        appendLine(shell.stdout.ifBlank { "(empty)" })
                        appendLine("--- stderr ---")
                        append(shell.stderr.ifBlank { "(empty)" })
                    },
                )
            }.onFailure { error ->
                CapabilityState.publish(label, error.stackTraceToString())
            }
        }
    }

    /**
     * 0.1T feasibility spike only.
     *
     * Shizuku 13.1.5 still contains deprecated newProcess internally, but plans to remove it
     * in API 14. Reflection is used here only to prove shell-level capabilities. The production
     * bridge will use UserService/system binder APIs after feasibility is established.
     */
    private fun runShellReflective(command: String): ShellResult {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true

        val remoteProcess = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null,
        ) ?: error("Shizuku.newProcess returned null")

        val processClass = remoteProcess.javaClass
        val stdoutStream = processClass.getMethod("getInputStream")
            .invoke(remoteProcess) as InputStream
        val stderrStream = processClass.getMethod("getErrorStream")
            .invoke(remoteProcess) as InputStream

        val stdout = stdoutStream.bufferedReader().use { it.readText() }
        val stderr = stderrStream.bufferedReader().use { it.readText() }
        val exitCode = processClass.getMethod("waitFor")
            .invoke(remoteProcess) as Int

        runCatching {
            processClass.getMethod("destroy").invoke(remoteProcess)
        }

        return ShellResult(
            exitCode = exitCode,
            stdout = stdout.trim(),
            stderr = stderr.trim(),
        )
    }

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
