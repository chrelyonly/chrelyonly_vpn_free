package com.non.human.xray.core.vpn
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.v2ray.ang.service.TProxyService
class TunManager(private val vpnService: VpnService) {
    private var tProxyService: TProxyService? = null
    fun startTun2Socks(vpnInterface: ParcelFileDescriptor) {
        tProxyService = TProxyService(vpnService, vpnInterface)
        tProxyService?.startTun2Socks()
    }
    fun stopTun2Socks() {
        tProxyService?.stopTun2Socks()
        tProxyService = null
    }
    fun isTunRunning(): Boolean = tProxyService != null
}
