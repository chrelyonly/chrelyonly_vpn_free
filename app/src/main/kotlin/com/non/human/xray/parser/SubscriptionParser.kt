package com.non.human.xray.parser

import com.non.human.xray.data.OutboundNode
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object SubscriptionParser {
    fun parse(rawContent: String): List<OutboundNode> {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) return emptyList()

        val candidates = buildList {
            add(trimmed)
            tryBase64Decode(trimmed)?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
        }

        candidates.forEach { candidate ->
            val nodes = parseCandidate(candidate)
            if (nodes.isNotEmpty()) return deduplicate(nodes)
        }
        return emptyList()
    }

    private fun parseCandidate(content: String): List<OutboundNode> {
        parseJson(content).takeIf { it.isNotEmpty() }?.let { return it }
        parseClashLikeYaml(content).takeIf { it.isNotEmpty() }?.let { return it }
        return content
            .lines()
            .mapNotNull { NodeParser.parse(it) }
    }

    private fun parseJson(content: String): List<OutboundNode> {
        if (!content.startsWith("{") && !content.startsWith("[")) return emptyList()
        return runCatching {
            val root: Any = if (content.startsWith("[")) JSONArray(content) else JSONObject(content)
            extractStructured(root)
        }.getOrDefault(emptyList())
    }

    private fun extractStructured(root: Any): List<OutboundNode> {
        if (root is JSONArray) {
            return (0 until root.length()).mapNotNull { index ->
                root.optJSONObject(index)?.let { NodeParser.fromProxyMap(it) }
            }
        }
        if (root !is JSONObject) return emptyList()
        root.optJSONArray("outbounds")?.let { outbounds ->
            return (0 until outbounds.length()).mapNotNull { index ->
                outbounds.optJSONObject(index)?.let { NodeParser.fromOutbound(it) }
            }
        }
        listOf("proxies", "nodes", "servers").forEach { key ->
            root.optJSONArray(key)?.let { array ->
                return (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { NodeParser.fromProxyMap(it) }
                }
            }
        }
        return NodeParser.fromProxyMap(root)?.let { listOf(it) } ?: emptyList()
    }

    private fun parseClashLikeYaml(content: String): List<OutboundNode> {
        if (!content.contains("proxies:")) return emptyList()
        val nodes = mutableListOf<OutboundNode>()
        var current: MutableMap<String, String>? = null
        fun flush() {
            val map = current ?: return
            if (map.isNotEmpty()) nodes += mapToNode(map) ?: return
        }

        content.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("- ")) {
                flush()
                current = mutableMapOf()
                parseYamlPair(line.removePrefix("- "))?.let { current?.put(it.first, it.second) }
            } else if (current != null) {
                parseYamlPair(line)?.let { current?.put(it.first, it.second) }
            }
        }
        flush()
        return nodes
    }

    private fun mapToNode(map: Map<String, String>): OutboundNode? {
        val json = JSONObject()
        map.forEach { (key, value) ->
            val normalized = value.trim('"', '\'')
            when {
                normalized.equals("true", ignoreCase = true) -> json.put(key, true)
                normalized.equals("false", ignoreCase = true) -> json.put(key, false)
                normalized.toIntOrNull() != null -> json.put(key, normalized.toInt())
                else -> json.put(key, normalized)
            }
        }
        return NodeParser.fromProxyMap(json)
    }

    private fun parseYamlPair(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val key = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        if (key.isBlank() || value.isBlank()) return null
        return key to value
    }

    private fun tryBase64Decode(content: String): String? {
        return runCatching {
            val normalized = content.replace("\\s".toRegex(), "").let {
                it + "=".repeat((4 - it.length % 4) % 4)
            }
            val decoder = if (normalized.contains("-") || normalized.contains("_")) {
                Base64.getUrlDecoder()
            } else {
                Base64.getDecoder()
            }
            String(decoder.decode(normalized), StandardCharsets.UTF_8).takeIf {
                it.contains("://") || it.startsWith("{") || it.startsWith("[") || it.contains("proxies:")
            }
        }.getOrNull()
    }

    private fun deduplicate(nodes: List<OutboundNode>): List<OutboundNode> {
        val seen = linkedSetOf<String>()
        return nodes.filter { node ->
            seen.add("${node.protocol}|${node.address}|${node.port}|${node.name}")
        }
    }
}
