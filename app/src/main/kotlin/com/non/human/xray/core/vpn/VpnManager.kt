package com.non.human.xray.core.vpn
import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * VPN 管理器
 * 负责系统 VPN 接口的建立，以及前台服务通知的创建与更新。
 */
class VpnManager(private val vpnService: VpnService) {
    companion object {
        private const val SESSION_NAME = "xray-plus"
    }
    private var vpnInterface: ParcelFileDescriptor? = null

    /**
     * 调用安卓创建vpn接口
     */
    fun establishVpnInterface(): ParcelFileDescriptor? {
        return try {
            vpnInterface = vpnService.Builder()
                .setSession(SESSION_NAME)
                .setMtu(9000)
                .addAddress("172.19.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("223.5.5.5")
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(vpnService.packageName)
                .establish()
            vpnInterface
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 关闭vpn服务
     */
    fun closeVpnInterface() {
        vpnInterface?.close()
        vpnInterface = null
    }

    /**
     * 是否运行
     */
    fun isVpnEstablished(): Boolean = vpnInterface != null
}
