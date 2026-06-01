package com.non.human.xray.data

import org.json.JSONArray
import org.json.JSONObject

data class Subscription(
    var name: String,
    var url: String,
    var nodes: MutableList<OutboundNode> = mutableListOf(),
    var lastUpdateMillis: Long = 0L,
) {
    fun toJson(): JSONObject {
        val nodeArray = JSONArray()
        nodes.forEach { nodeArray.put(it.toJson()) }
        return JSONObject()
            .put("name", name)
            .put("url", url)
            .put("nodes", nodeArray)
            .put("lastUpdateMillis", lastUpdateMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): Subscription {
            val nodeArray = json.optJSONArray("nodes") ?: JSONArray()
            val nodes = mutableListOf<OutboundNode>()
            for (index in 0 until nodeArray.length()) {
                val node = nodeArray.optJSONObject(index) ?: continue
                nodes += OutboundNode.fromJson(node)
            }
            return Subscription(
                name = json.optString("name", "Subscription"),
                url = json.optString("url", ""),
                nodes = nodes,
                lastUpdateMillis = json.optLong("lastUpdateMillis", 0L),
            )
        }
    }
}
