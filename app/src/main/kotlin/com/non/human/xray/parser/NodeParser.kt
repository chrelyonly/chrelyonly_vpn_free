package com.non.human.xray.parser

import android.net.Uri
import androidx.core.net.toUri
import com.non.human.xray.data.OutboundNode
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

object NodeParser {
    fun parse(line: String): OutboundNode? {
        val text = line.trim()
        if (text.isBlank()) return null

        return runCatching {
            when {
                text.startsWith("vless://", ignoreCase = true) -> parseStandardUri(text, "vless")
                text.startsWith("trojan://", ignoreCase = true) -> parseStandardUri(text, "trojan")
                text.startsWith("vmess://", ignoreCase = true) -> parseVmess(text)
                text.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(text)
                text.startsWith("socks://", ignoreCase = true) -> parseSimpleProxy(text, "socks")
                text.startsWith("http://", ignoreCase = true) -> parseSimpleProxy(text, "http")
                text.startsWith("hysteria2://", ignoreCase = true) ||
                    text.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(text)
                text.startsWith("{") -> fromOutbound(JSONObject(text))
                else -> null
            }
        }.getOrNull()
    }

    fun fromOutbound(outbound: JSONObject): OutboundNode? {
        val protocol = outbound.optString("protocol").lowercase()
        if (protocol.isBlank()) return null
        val endpoint = extractEndpoint(outbound)
        val tag = outbound.optString("tag")
        val name = when {
            tag.isNotBlank() && tag != "proxy" -> tag
            endpoint.first.isNotBlank() -> "${protocol.uppercase()} ${endpoint.first}:${endpoint.second}"
            else -> protocol.uppercase()
        }
        val copy = JSONObject(outbound.toString())
        if (!copy.has("tag")) copy.put("tag", if (protocol == "freedom") "direct" else "proxy")
        return OutboundNode(
            name = name,
            protocol = protocol,
            address = endpoint.first,
            port = endpoint.second,
            outboundJson = copy.toString(),
        )
    }

    fun fromProxyMap(proxy: JSONObject): OutboundNode? {
        val type = proxy.optString("type", proxy.optString("protocol")).lowercase()
        if (type.isBlank()) return null
        if (proxy.has("protocol") && proxy.has("settings")) return fromOutbound(proxy)

        val protocol = when (type) {
            "ss" -> "shadowsocks"
            "socks5" -> "socks"
            "hysteria2", "hy2" -> "hysteria"
            else -> type
        }
        val server = proxy.optString("server", proxy.optString("address", proxy.optString("host")))
        val port = proxy.optInt("port", 443)
        val name = proxy.optString("name", proxy.optString("ps", "${protocol.uppercase()} $server:$port"))

        val outbound = when (protocol) {
            "vless" -> buildVnextOutbound(
                protocol = "vless",
                address = server,
                port = port,
                id = proxy.optString("uuid", proxy.optString("id")),
                encryption = "none",
                alterId = null,
                flow = proxy.optString("flow").ifBlank { null },
                streamSettings = structuredStream(proxy, protocol),
            )
            "vmess" -> buildVnextOutbound(
                protocol = "vmess",
                address = server,
                port = port,
                id = proxy.optString("uuid", proxy.optString("id")),
                encryption = proxy.optString("cipher", proxy.optString("security", "auto")),
                alterId = proxy.optInt("alterId", proxy.optInt("alter-id", 0)),
                flow = null,
                streamSettings = structuredStream(proxy, protocol),
            )
            "trojan" -> JSONObject()
                .put("protocol", "trojan")
                .put("tag", "proxy")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", server)
                                .put("port", port)
                                .put("password", proxy.optString("password", proxy.optString("id"))),
                        ),
                    ),
                )
                .put("streamSettings", structuredStream(proxy, protocol))
            "shadowsocks" -> JSONObject()
                .put("protocol", "shadowsocks")
                .put("tag", "proxy")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", server)
                                .put("port", port)
                                .put("method", proxy.optString("cipher", proxy.optString("method", "aes-128-gcm")))
                                .put("password", proxy.optString("password")),
                        ),
                    ),
                )
            "socks", "http" -> JSONObject()
                .put("protocol", protocol)
                .put("tag", "proxy")
                .put(
                    "settings",
                    JSONObject()
                        .put("address", server)
                        .put("port", port)
                        .apply {
                            proxy.optString("username").ifBlank { null }?.let { put("user", it) }
                            proxy.optString("password").ifBlank { null }?.let { put("pass", it) }
                        },
                )
            else -> JSONObject(proxy.toString()).put("protocol", protocol).put("tag", "proxy")
        }

        return OutboundNode(name = name, protocol = protocol, address = server, port = port, outboundJson = outbound.toString())
    }

    private fun parseStandardUri(text: String, protocol: String): OutboundNode {
        val uri = text.toUri()
        val name = uri.fragment?.takeIf { it.isNotBlank() }?.let { Uri.decode(it) } ?: protocol.uppercase()
        val id = uri.userInfo ?: ""
        val security = uri.getQueryParameter("security")
            ?: uri.getQueryParameter("tls")?.let { if (it == "1" || it == "true" || it == "tls") "tls" else it }
            ?: "none"
        val streamSettings = streamSettings(
            network = uri.getQueryParameter("type") ?: uri.getQueryParameter("network") ?: "raw",
            security = security,
            host = uri.getQueryParameter("host"),
            path = uri.getQueryParameter("path"),
            serviceName = uri.getQueryParameter("serviceName"),
            sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("servername"),
            publicKey = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey"),
            shortId = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId"),
            spiderX = uri.getQueryParameter("spx") ?: uri.getQueryParameter("spiderX"),
            mldsa64Verify = uri.getQueryParameter("pqv"),
            fingerprint = uri.getQueryParameter("fp") ?: uri.getQueryParameter("fingerprint"),
            alpn = uri.getQueryParameter("alpn"),
            ech = uri.getQueryParameter("ech"),
        )
        val outbound = if (protocol == "trojan") {
            JSONObject()
                .put("protocol", "trojan")
                .put("tag", "proxy")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", uri.host ?: "")
                                .put("port", normalizedPort(uri, 443))
                                .put("password", id),
                        ),
                    ),
                )
                .put("streamSettings", streamSettings)
        } else {
            buildVnextOutbound(
                protocol = "vless",
                address = uri.host ?: "",
                port = normalizedPort(uri, 443),
                id = id,
                encryption = uri.getQueryParameter("encryption") ?: "none",
                alterId = null,
                flow = uri.getQueryParameter("flow"),
                streamSettings = streamSettings,
            )
        }

        return OutboundNode(name, protocol, uri.host ?: "", normalizedPort(uri, 443), outbound.toString())
    }

    private fun parseVmess(text: String): OutboundNode? {
        val decoded = decodeBase64(text.removePrefix("vmess://")) ?: return null
        val data = JSONObject(decoded)
        val host = data.optString("add")
        val port = data.optString("port", "443").toIntOrNull() ?: 443
        val security = when (data.optString("tls").lowercase()) {
            "tls", "true", "1" -> "tls"
            "reality" -> "reality"
            else -> "none"
        }
        val outbound = buildVnextOutbound(
            protocol = "vmess",
            address = host,
            port = port,
            id = data.optString("id"),
            encryption = data.optString("scy", "auto"),
            alterId = data.optString("aid", "0").toIntOrNull() ?: 0,
            flow = null,
            streamSettings = streamSettings(
                network = data.optString("net", "tcp"),
                security = security,
                host = data.optString("host").ifBlank { null },
                path = data.optString("path").ifBlank { null },
                serviceName = data.optString("path").ifBlank { null },
                sni = data.optString("sni").ifBlank { null },
                publicKey = null,
                shortId = null,
                spiderX = null,
                mldsa64Verify = null,
                fingerprint = null,
                alpn = data.optString("alpn").ifBlank { null },
                ech = data.optString("ech").ifBlank { null },
            ),
        )
        return OutboundNode(data.optString("ps", "VMess"), "vmess", host, port, outbound.toString())
    }

    private fun parseShadowsocks(text: String): OutboundNode? {
        val uri = text.toUri()
        val name = uri.fragment?.takeIf { it.isNotBlank() }?.let { Uri.decode(it) } ?: "Shadowsocks"
        val host = uri.host
        val port = normalizedPort(uri, 8388)
        var userInfo = uri.userInfo ?: ""

        if (host.isNullOrBlank()) {
            val body = text.removePrefix("ss://").substringBefore("#")
            val decoded = decodeBase64(body) ?: return null
            val at = decoded.lastIndexOf('@')
            val credential = decoded.substring(0, at)
            val endpoint = decoded.substring(at + 1)
            val method = credential.substringBefore(":")
            val password = credential.substringAfter(":", "")
            val parsedHost = endpoint.substringBeforeLast(":")
            val parsedPort = endpoint.substringAfterLast(":", "8388").toIntOrNull() ?: 8388
            return shadowsocksNode(name, parsedHost, parsedPort, method, password)
        }

        if (!userInfo.contains(":")) {
            userInfo = decodeBase64(userInfo) ?: userInfo
        }
        val method = userInfo.substringBefore(":", "aes-128-gcm")
        val password = userInfo.substringAfter(":", "")
        return shadowsocksNode(name, host, port, method, password)
    }

    private fun parseSimpleProxy(text: String, protocol: String): OutboundNode {
        val uri = text.toUri()
        val name = uri.fragment?.takeIf { it.isNotBlank() }?.let { Uri.decode(it) } ?: protocol.uppercase()
        val user = uri.userInfo?.substringBefore(":").orEmpty()
        val pass = uri.userInfo?.substringAfter(":", "").orEmpty()
        val outbound = JSONObject()
            .put("protocol", protocol)
            .put("tag", "proxy")
            .put(
                "settings",
                JSONObject()
                    .put("address", uri.host ?: "")
                    .put("port", normalizedPort(uri, if (protocol == "http") 80 else 1080))
                    .apply {
                        if (user.isNotBlank()) put("user", user)
                        if (pass.isNotBlank()) put("pass", pass)
                    },
            )
        return OutboundNode(name, protocol, uri.host ?: "", normalizedPort(uri, 1080), outbound.toString())
    }

    private fun parseHysteria2(text: String): OutboundNode {
        val uri = text.toUri()
        val name = uri.fragment?.takeIf { it.isNotBlank() }?.let { Uri.decode(it) } ?: "Hysteria2"
        val outbound = JSONObject()
            .put("protocol", "hysteria")
            .put("tag", "proxy")
            .put(
                "settings",
                JSONObject()
                    .put("address", uri.host ?: "")
                    .put("port", normalizedPort(uri, 443))
                    .put("version", 2),
            )
            .put(
                "streamSettings",
                streamSettings(
                    network = "hysteria",
                    security = "tls",
                    host = null,
                    path = null,
                    serviceName = null,
                    sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer"),
                    publicKey = null,
                    shortId = null,
                    spiderX = null,
                    mldsa64Verify = null,
                    fingerprint = uri.getQueryParameter("fp"),
                    alpn = "h3",
                    ech = null,
                ).put("hysteriaSettings", JSONObject().put("auth", uri.userInfo ?: "").put("version", 2 ?: "")),
            )
        return OutboundNode(name, "hysteria", uri.host ?: "", normalizedPort(uri, 443), outbound.toString())
    }

    private fun shadowsocksNode(name: String, host: String, port: Int, method: String, password: String): OutboundNode {
        val outbound = JSONObject()
            .put("protocol", "shadowsocks")
            .put("tag", "proxy")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("method", method)
                            .put("password", password),
                    ),
                ),
            )
        return OutboundNode(name, "shadowsocks", host, port, outbound.toString())
    }

    private fun buildVnextOutbound(
        protocol: String,
        address: String,
        port: Int,
        id: String,
        encryption: String,
        alterId: Int?,
        flow: String?,
        streamSettings: JSONObject,
    ): JSONObject {
        val user = JSONObject().put("id", id)
        if (protocol == "vless") {
            user.put("encryption", encryption.ifBlank { "none" })
            if (!flow.isNullOrBlank()) user.put("flow", flow)
        } else {
            user.put("security", encryption.ifBlank { "auto" })
            user.put("alterId", alterId ?: 0)
        }
        return JSONObject()
            .put("protocol", protocol)
            .put("tag", "proxy")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", address)
                            .put("port", port)
                            .put("users", JSONArray().put(user)),
                    ),
                ),
            )
            .put("streamSettings", streamSettings)
    }

    private fun structuredStream(proxy: JSONObject, protocol: String): JSONObject {
        val network = proxy.optString("network", proxy.optString("net", "tcp"))
        val tls = proxy.opt("tls")
        val security = when {
            protocol == "hysteria" -> "tls"
            proxy.optJSONObject("reality-opts") != null -> "reality"
            tls == true || tls?.toString()?.lowercase() in setOf("true", "tls", "1") -> "tls"
            else -> "none"
        }
        val wsOpts = proxy.optJSONObject("ws-opts")
        val grpcOpts = proxy.optJSONObject("grpc-opts")
        val realityOpts = proxy.optJSONObject("reality-opts")
        val headers = wsOpts?.optJSONObject("headers")
        return streamSettings(
            network = if (protocol == "hysteria") "hysteria" else network,
            security = security,
            host = headers?.optString("Host") ?: headers?.optString("host") ?: proxy.optString("host").ifBlank { null },
            path = wsOpts?.optString("path") ?: proxy.optString("path").ifBlank { null },
            serviceName = grpcOpts?.optString("grpc-service-name") ?: grpcOpts?.optString("serviceName"),
            sni = proxy.optString("servername", proxy.optString("serverName", proxy.optString("sni"))).ifBlank { null },
            publicKey = realityOpts?.optString("public-key") ?: realityOpts?.optString("publicKey") ?: proxy.optString("pbk").ifBlank { null },
            shortId = realityOpts?.optString("short-id") ?: realityOpts?.optString("shortId") ?: proxy.optString("sid").ifBlank { null },
            spiderX = proxy.optString("spx").ifBlank { null },
            mldsa64Verify = proxy.optString("pqv").ifBlank { null },
            fingerprint = proxy.optString("client-fingerprint", proxy.optString("fp", "chrome")),
            alpn = proxy.optString("alpn").ifBlank { null },
            ech = proxy.optString("ech").ifBlank { null },
        )
    }

    private fun streamSettings(
        network: String,
        security: String,
        host: String?,
        path: String?,
        serviceName: String?,
        sni: String?,
        publicKey: String?,
        shortId: String?,
        spiderX: String?,
        mldsa64Verify: String?,
        fingerprint: String?,
        alpn: String?,
        ech: String?,
    ): JSONObject {
        val stream = JSONObject().put("network", network.ifBlank { "tcp" }).put("security", security.ifBlank { "none" })
        if (security == "tls") {
            stream.put(
                "tlsSettings",
                JSONObject()
                    .put("allowInsecure", true)
                    .put("fingerprint", fingerprint ?: "chrome")
                    .apply {
                        if (!sni.isNullOrBlank()) put("serverName", sni)
                        if (!alpn.isNullOrBlank()) put("alpn", JSONArray(alpn.split(",").map { it.trim() }))
                        if (!ech.isNullOrBlank()) {
                            put("echConfigList", ech)
                            put("echForceQuery", "full")
                        }
                    },
            )
        } else if (security == "reality") {
            stream.put(
                "realitySettings",
                JSONObject()
                    .put("show", false)
                    .put("fingerprint", fingerprint ?: "chrome")
                    .apply {
                        if (!sni.isNullOrBlank()) put("serverName", sni)
                        if (!publicKey.isNullOrBlank()) put("publicKey", publicKey)
                        if (!shortId.isNullOrBlank()) put("shortId", shortId)
                        if (!spiderX.isNullOrBlank()) put("spiderX", spiderX)
                        if (!spiderX.isNullOrBlank()) put("mldsa64Verify", mldsa64Verify)
                    },
            )
        }
        when (network) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject().apply {
                    if (!path.isNullOrBlank()) put("path", path)
                    val headers = JSONObject()
                    if (!host.isNullOrBlank()) {
                        put("host", host)
                    }
                    headers.put("User-Agent", "chrome")
                    put("headers", headers)
                },
            )
            "grpc" -> stream.put("grpcSettings", JSONObject().apply { if (!serviceName.isNullOrBlank()) put("serviceName", serviceName) })
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().apply { if (!path.isNullOrBlank()) put("path", path) })
            "hysteria" -> Unit
        }
        return stream
    }

    private fun extractEndpoint(outbound: JSONObject): Pair<String, Int> {
        val settings = outbound.optJSONObject("settings") ?: return "" to 0
        settings.optJSONArray("vnext")?.optJSONObject(0)?.let {
            return it.optString("address") to it.optInt("port")
        }
        settings.optJSONArray("servers")?.optJSONObject(0)?.let {
            return it.optString("address") to it.optInt("port")
        }
        return settings.optString("address") to settings.optInt("port")
    }

    private fun normalizedPort(uri: Uri, fallback: Int): Int = if (uri.port > 0) uri.port else fallback

    private fun decodeBase64(value: String): String? {
        return runCatching {
            val normalized = value.replace("\\s".toRegex(), "").let {
                it + "=".repeat((4 - it.length % 4) % 4)
            }
            val decoder = if (normalized.contains("-") || normalized.contains("_")) {
                Base64.getUrlDecoder()
            } else {
                Base64.getDecoder()
            }
            String(decoder.decode(normalized), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}
