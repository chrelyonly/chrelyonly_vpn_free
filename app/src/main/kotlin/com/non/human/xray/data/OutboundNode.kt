package com.non.human.xray.data

import org.json.JSONObject

data class OutboundNode(
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val outboundJson: String,
) {
    val endpoint: String
        get() = if (address.isBlank()) "unknown" else "$address:$port"

    fun toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("protocol", protocol)
            .put("address", address)
            .put("port", port)
            .put("outboundJson", outboundJson)
    }

    companion object {
        fun fromJson(json: JSONObject): OutboundNode {
            return OutboundNode(
                name = json.optString("name", "Unnamed Node"),
                protocol = json.optString("protocol", "vless"),
                address = json.optString("address", ""),
                port = json.optInt("port", 443),
                outboundJson = json.optString("outboundJson", "{}"),
            )
        }
    }
}
