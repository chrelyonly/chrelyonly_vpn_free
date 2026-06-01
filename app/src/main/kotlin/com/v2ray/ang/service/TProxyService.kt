package com.v2ray.ang.service
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * 负责管理 hev-socks5-tunnel 进程。
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
) {
    companion object {
        private const val TAG = "TProxyService"

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
            } catch (e: Exception) {
                Log.e(TAG, "加载 libhev-socks5-tunnel.so 失败: ${e.message}")
            }
        }
    }

    fun startTun2Socks() {
        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }

        Log.i(TAG, "已生成 tun2socks 配置")

        try {
            Log.i(TAG, "正在启动原生隧道，文件描述符(FD): ${vpnInterface.fd}")
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
            Log.i(TAG, "原生隧道启动指令已发送")
            TProxyGetStats();
        } catch (e: Exception) {
            Log.e(TAG, "启动失败: ${e.message}")
        }
    }

    fun stopTun2Socks() {
        try {
            Log.i(TAG, "正在停止原生隧道...")
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "停止失败: ${e.message}")
        }
    }

    /**
     * 生成配置 需要代理到目标地址
     */
    private fun buildConfig(): String {
        return """
tunnel:
  mtu: 9000
  ipv4: 172.19.0.1
socks5:
  address: 127.0.0.1
  port: 10808
  udp: 'udp'
        """.trimIndent()
    }
}
