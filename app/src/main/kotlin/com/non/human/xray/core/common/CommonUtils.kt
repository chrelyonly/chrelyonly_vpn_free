package com.non.human.xray.core.common
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通用工具类
 * 提供日志发送、状态更新等通用功能
 */
class CommonUtils(private val context: Context) {
    companion object {
        const val ACTION_LOG = "com.non.human.xray.LOG"
        const val ACTION_STATUS = "com.non.human.xray.STATUS"
        private const val TAG_SYSTEM = "VPN_System"
    }

    /**
     * 发送事件日志
     * @param type 日志类型
     * @param msg 日志内容
     */
    fun sendEventLog(type: String, msg: String) {
        Log.d(TAG_SYSTEM, "[$type] $msg")
    }

    /**
     * 发送状态更新
     * @param vpn VPN状态
     * @param xray Xray状态
     * @param tun Tun状态
     */
    fun sendStatusUpdate(
        vpn: Boolean = false,
        xray: Boolean = false,
        tun: Boolean = false,
        connectMsg: String = "",
    ) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra("vpn", vpn)
            putExtra("xray", xray)
            putExtra("tun", tun)
            putExtra("connectMsg", connectMsg)
        }
        context.sendBroadcast(intent)
    }

}
