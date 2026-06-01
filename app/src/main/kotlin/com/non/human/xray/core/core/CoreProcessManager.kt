package com.non.human.xray.core.core
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class CoreProcessManager(private val context: Context) {
    private val tag = "内核进程"

    // 当前运行的进程
    private var coreProcess: Process? = null

    // 单线程池（顺序处理日志 + 进程状态）
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 启动内核（如 Xray）
     */
    fun startCoreProcess(
        binPath: String,
        configPath: String,
    ) {
        try {
            // ===== 防止重复启动 =====
            if (coreProcess != null) {
                val msg = "进程已在运行，无需重复启动"
                Log.w(tag, msg)
                return
            }

            // ===== 确保资源文件存在（geo 文件）=====
            ensureAssetAvailable("geosite.dat")
            ensureAssetAvailable("geoip.dat")

            val binFile = File(binPath)
            val configFile = File(configPath)

            // ===== 基础校验 =====
            if (!binFile.exists()) {
                throw RuntimeException("二进制文件不存在: $binPath")
            }

            if (!configFile.exists()) {
                throw RuntimeException("配置文件不存在: $configPath")
            }

            // ===== 设置执行权限（非常关键）=====
            if (!binFile.canExecute()) {
                val success = binFile.setExecutable(true)
                Log.d(tag, "设置执行权限: $success")
            }

            Log.d(tag, "====== 准备启动内核 ======")
            Log.d(tag, "二进制路径: ${binFile.absolutePath}")
            Log.d(tag, "配置路径: ${configFile.absolutePath}")
            Log.d(tag, "工作目录: ${context.filesDir.absolutePath}")

            // ===== 构建进程 =====
            val processBuilder = ProcessBuilder(
                binFile.absolutePath,
                "run",
                "-config",
                configFile.absolutePath
            ).apply {
                // 设置资源路径（geoip / geosite）
                environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            }

            val process = processBuilder
                .directory(context.filesDir)     // 设置工作目录
                .redirectErrorStream(true)       // 合并错误流到标准输出
                .start()

            coreProcess = process

            val startMsg = "内核启动成功"
            Log.i(tag, startMsg)

            // ===== 读取日志 =====
            executor.execute {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(tag, "【内核日志】$line")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "日志读取被中断: ${e.message}")
                }
            }

            // ===== 等待进程结束 =====
            executor.execute {
                try {
                    val exitCode = process.waitFor()
                    val msg = "内核已退出，退出码: $exitCode"
                    Log.w(tag, msg)
                } catch (e: Exception) {
                    Log.e(tag, "等待进程结束异常", e)
                } finally {
                    coreProcess = null
                }
            }

        } catch (e: Exception) {
            val errMsg = "启动失败: ${e.message}"
            Log.e(tag, errMsg, e)
        }
    }

    /**
     * 停止内核
     */
    fun stopCoreProcess() {
        try {
            coreProcess?.destroy()
            coreProcess = null
            Log.i(tag, "内核已停止")
        } catch (e: Exception) {
            Log.e(tag, "停止失败", e)
        }
    }

    /**
     * 释放资源（建议在 Service / Activity 销毁时调用）
     */
    fun release() {
        stopCoreProcess()
        executor.shutdownNow()
        Log.d(tag, "线程池已释放")
    }

    /**
     * 确保 asset 文件存在（不存在就从 assets 拷贝）
     */
    private fun ensureAssetAvailable(name: String): File {
        val outFile = File(context.filesDir, name)

        if (!outFile.exists()) {
            Log.d(tag, "资源不存在，开始拷贝: $name")

            context.assets.open(name).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(tag, "资源拷贝完成: ${outFile.absolutePath}")
        } else {
            Log.d(tag, "资源已存在: ${outFile.absolutePath}")
        }

        return outFile
    }




    fun isProcessAlive(): Boolean = try {
        coreProcess?.exitValue()
        false
    } catch (_: Exception) {
        true
    }




}
