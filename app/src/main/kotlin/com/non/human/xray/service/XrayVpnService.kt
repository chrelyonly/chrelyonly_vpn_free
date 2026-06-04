package com.non.human.xray.service
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.non.human.xray.core.common.CommonUtils
import com.non.human.xray.core.core.CoreProcessManager
import com.non.human.xray.core.vpn.TunManager
import com.non.human.xray.core.vpn.VpnManager
import com.non.human.xray.core.xray.XrayConfigBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * xray核心接口
 */
@SuppressLint("VpnServicePolicy")
class XrayVpnService : VpnService() {
    private lateinit var commonUtils: CommonUtils
    private lateinit var coreProcessManager: CoreProcessManager
    private lateinit var xrayConfigBuilder: XrayConfigBuilder
    private lateinit var vpnManager: VpnManager
    private lateinit var tunManager: TunManager
//    当前的节点
    private var outboundJson: String? = null


    private fun createNotification(): Notification {
        val channelId = "vpn_service"

        val channel = NotificationChannel(
            channelId,
            "VPN Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("自动消灭小恐怖中...")
            .setContentText("已连接至宗门传送点")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1,createNotification())
        ensureCoreModules()

        when (intent?.action) {
            "STOP_VPN" -> {
                commonUtils.sendEventLog("VPN_System", "收到停止请求")
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }

            "RESTART_CORE", "RESTART_XRAY" -> {
                commonUtils.sendEventLog("VPN_System", "收到内核/节点切换请求")
                val newOutboundJson = intent.getStringExtra("CONFIG_OUTBOUND_JSON")
                if (newOutboundJson.isNullOrBlank()) {
                    commonUtils.sendEventLog("VPN_System", "切换失败，节点配置为空")
                    return START_NOT_STICKY
                }
                restartCore(newOutboundJson)
                return START_NOT_STICKY
            }

            "TEST_SPEED" -> {
                testSpeedFunc()
                return START_STICKY
            }
        }

        outboundJson = intent?.getStringExtra("CONFIG_OUTBOUND_JSON")
        if (outboundJson.isNullOrBlank()) {
            commonUtils.sendEventLog("VPN_System", "启动失败，节点配置为空")
            stopSelf()
            return START_NOT_STICKY
        }
        Thread { establishVpn() }.start()
        return START_STICKY
    }
    private fun ensureCoreModules() {
        if (!::commonUtils.isInitialized) {
            commonUtils = CommonUtils(this)
            coreProcessManager = CoreProcessManager(this)
            xrayConfigBuilder = XrayConfigBuilder()
            vpnManager = VpnManager(this)
            tunManager = TunManager(this)
        }
    }

    /**
     * 启动vpn
     */
    private fun establishVpn() {
        try {
            commonUtils.sendEventLog("VPN_System", "正在准备安全隧道")
//            寻找xray二进制
            val coreBinary = resolveCurrentCoreBinary()
//            生成并写入文件
            val configJson = xrayConfigBuilder.buildXrayConfig(outboundJson)
            val configFile = currentConfigFile()
            configFile.writeText(configJson)
            commonUtils.sendEventLog("VPN_Core", "正在启动 xray 内核")
//            启动内核
            coreProcessManager.startCoreProcess(
                binPath = coreBinary.absolutePath,
                configPath = configFile.absolutePath,
            )

            commonUtils.sendEventLog("VPN_Tun", "正在创建 Android VPN 接口")
//            启动系统vpn
            val vpnInterface = vpnManager.establishVpnInterface() ?: throw Exception("Android VPN 接口创建失败")
            commonUtils.sendEventLog("VPN_Tun", "正在启动 tun2socks 转发")
            tunManager.startTun2Socks(vpnInterface)
        } catch (e: Exception) {
            commonUtils.sendEventLog("VPN_System", "启动失败: ${e.message}")
            stopVpn()
            stopSelf()
        }
    }


    /**
     * 测速
     */
    public fun testSpeedFunc() {
        Thread {
            var connectMsg = "";
            try {
                // 开始时间
                val start = System.currentTimeMillis()
                val proxy = Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", 10808)
                )
                // 发起请求
                val client = OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://www.google.com/generate_204")
                    .header("User-Agent", "XrayPlusNative/1.0")
                    .build()
                client.newCall(request)
                    .execute()
                // 结束时间
                val end = System.currentTimeMillis()
                // 耗时
                val cost = end - start
                // 拼接结果字符串
                connectMsg = """访问谷歌耗时: $cost ms""".trimIndent()
            } catch (e: Exception) {

                connectMsg = """
            连接失败
            原因: 未知错误
            错误信息: ${e.message}
        """.trimIndent()
            }
            if (connectMsg != ""){
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, connectMsg, Toast.LENGTH_LONG).show()
                }
            }


//        commonUtils.sendStatusUpdate(
//            vpn = ::vpnManager.isInitialized && vpnManager.isVpnEstablished(),
//            xray = ::coreProcessManager.isInitialized && coreProcessManager.isProcessAlive(),
//            tun = ::tunManager.isInitialized && tunManager.isTunRunning(),
//            connectMsg = connectMsg
//        )
        }.start()

    }

    /**
     * 停用vpn
     */
    private fun stopVpn() {

        if (::tunManager.isInitialized) {
            tunManager.stopTun2Socks()
        }
        if (::coreProcessManager.isInitialized) {
            coreProcessManager.stopCoreProcess()
        }
        if (::vpnManager.isInitialized) {
            vpnManager.closeVpnInterface()
        }
//        if (::commonUtils.isInitialized) {
//            sendStatusUpdate()
//        }
    }

    /**
     * 节点切换的时候重启xray
     */
    private fun restartCore(newOutboundJson: String) {
        try {
            commonUtils.sendEventLog("VPN_System", "正在切换节点/内核")
//            先停止内核
            coreProcessManager.stopCoreProcess()
//            设置新配置
            outboundJson = newOutboundJson
//            生成新的配置文件并写入
            val configFile = currentConfigFile()
            configFile.writeText(xrayConfigBuilder.buildXrayConfig(outboundJson))
//            二进制启动
            val coreBinary = resolveCurrentCoreBinary()
            coreProcessManager.startCoreProcess(
                binPath = coreBinary.absolutePath,
                configPath = configFile.absolutePath,
            )
            commonUtils.sendEventLog("VPN_System", "切换完成")
        } catch (e: Exception) {
            commonUtils.sendEventLog("VPN_System", "切换失败: ${e.message}")
        }
    }

    /**
     * 生成配置文件
     */
    private fun currentConfigFile(): File {
        return File(filesDir, "xray_config.json")
    }

    /**
     * 寻找二进制文件
     */
    private fun resolveCurrentCoreBinary(): File {
        val nativeLibraryDir = applicationContext.applicationInfo?.nativeLibraryDir
        val file = File(nativeLibraryDir, "libxray.so")
        if (file.exists()) {
            return file
        }
        throw Exception("未找到 xray 内核文件")
    }

    /**
     * 销毁时关闭
     */
    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
