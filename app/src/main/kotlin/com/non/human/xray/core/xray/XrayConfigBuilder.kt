package com.non.human.xray.core.xray

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject

class XrayConfigBuilder {

    /**
     * 生成配置
     */
    fun buildXrayConfig(
        outboundJson: String?,
    ): String {

        return try {
            val root = JSONObject()
            root["log"] = JSONObject.parseObject(
                """
    {
    "loglevel": "error"
  }
                
            """.trimIndent()
            )
            root["dns"] = buildDns()
            root["inbounds"] = buildInbounds()
//            出站地址
            root["outbounds"] = buildOutbounds(outboundJson)
//             路由
            root["routing"] = buildRouting()
            root["metrics"] = JSONObject.parseObject(
                """
    {
    "tag": "api"
  }
                
            """.trimIndent()
            )
            root["policy"] = JSONObject.parseObject(
                """
    {
    "system": {
      "statsOutboundUplink": true,
      "statsOutboundDownlink": true
    }
  }
            """.trimIndent()
            )
            root["stats"] = JSONObject()
            root.toJSONString()
        } catch (e: Exception) {
            e.printStackTrace()
            val root = JSONObject()
            root.toJSONString()
        }
    }

    /**
     * 生成入栈地址
     */
    private fun buildInbounds(): JSONArray {
        val inbounds = JSONArray()
        val inbound = """
    {
      "tag": "socks",
      "port": 10808,
      "listen": "0.0.0.0",
      "protocol": "mixed",
      "sniffing": {
        "enabled": true,
        "destOverride": [
          "http",
          "tls",
          "quic",
          "fakedns"
        ],
        "routeOnly": false
      },
      "settings": {
        "auth": "noauth",
        "udp": true,
        "allowTransparent": false
      }
    },
        """.trimIndent()
        inbounds[0] = JSONObject.parseObject(inbound)
        return inbounds
    }

    /**
     * 生成出站
     */
    private fun buildOutbounds(outboundJson: String?): JSONArray {
        val outbounds = JSONArray()
        val proxyOutbound = JSON.parseObject(outboundJson?.toByteArray(Charsets.UTF_8))
        proxyOutbound["tag"] = "proxy"
        proxyOutbound["mux"] = JSONObject.parseObject(
                """
    {
        "enabled": false,
        "concurrency": -1,
        "xudpConcurrency": 16,
        "xudpProxyUDP443": "reject"
      }
        """.trimIndent()
            )

        outbounds.add(proxyOutbound)

//        默认出站
        outbounds.add(
            JSONObject.parseObject(
                """
    {
      "tag": "direct",
      "protocol": "freedom"
    }
        """.trimIndent()
            )
        )
        outbounds.add(
            JSONObject.parseObject(
                """
    {
      "tag": "block",
      "protocol": "blackhole"
    }
        """.trimIndent()
            )
        )
        return outbounds
    }

    /**
     * 构建dns服务
     */
    private fun buildDns(): JSONObject {
        val dns = """
        {
        "hosts": {
          "dns.google": [
            "8.8.8.8",
            "8.8.4.4",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844"
          ],
          "dns.alidns.com": [
            "223.5.5.5",
            "223.6.6.6",
            "2400:3200::1",
            "2400:3200:baba::1"
          ],
          "one.one.one.one": [
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001"
          ],
          "1dot1dot1dot1.cloudflare-dns.com": [
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001"
          ],
          "cloudflare-dns.com": [
            "104.16.249.249",
            "104.16.248.249",
            "2606:4700::6810:f8f9",
            "2606:4700::6810:f9f9"
          ],
          "dns.cloudflare.com": [
            "104.16.132.229",
            "104.16.133.229",
            "2606:4700::6810:84e5",
            "2606:4700::6810:85e5"
          ],
          "dot.pub": [
            "1.12.12.12",
            "120.53.53.53"
          ],
          "doh.pub": [
            "1.12.12.12",
            "120.53.53.53"
          ],
          "dns.quad9.net": [
            "9.9.9.9",
            "149.112.112.112",
            "2620:fe::fe",
            "2620:fe::9"
          ],
          "dns.yandex.net": [
            "77.88.8.8",
            "77.88.8.1",
            "2a02:6b8::feed:0ff",
            "2a02:6b8:0:1::feed:0ff"
          ],
          "dns.sb": [
            "185.222.222.222",
            "2a09::"
          ],
          "dns.umbrella.com": [
            "208.67.220.220",
            "208.67.222.222",
            "2620:119:35::35",
            "2620:119:53::53"
          ],
          "dns.sse.cisco.com": [
            "208.67.220.220",
            "208.67.222.222",
            "2620:119:35::35",
            "2620:119:53::53"
          ],
          "engage.cloudflareclient.com": [
            "162.159.192.1"
          ]
        },
        "servers": [
          {
            "address": "https://dns.alidns.com/dns-query",
            "domains": [
              "domain:alidns.com",
              "domain:doh.pub",
              "domain:dot.pub",
              "domain:360.cn",
              "domain:onedns.net"
            ],
            "skipFallback": true,
            "tag": "direct-dns-1"
          },
          {
            "address": "https://cloudflare-dns.com/dns-query",
            "domains": [
              "geosite:google"
            ],
            "skipFallback": true
          },
          {
            "address": "https://dns.alidns.com/dns-query",
            "domains": [
              "geosite:private",
              "geosite:cn"
            ],
            "skipFallback": true,
            "tag": "direct-dns-2"
          },
          {
            "address": "223.5.5.5",
            "domains": [
              "full:dns.alidns.com",
              "full:cloudflare-dns.com"
            ],
            "skipFallback": true
          },
          "https://cloudflare-dns.com/dns-query"
        ],
        "tag": "dns-module"
      }
        """.trimIndent()
        return JSONObject.parseObject(dns)
    }

    /**
     * 生成路由
     */
    private fun buildRouting(): JSONObject {
        val rules = """
            {
    "domainStrategy": "AsIs",
    "rules": [
      {
        "type": "field",
        "inboundTag": [
          "api"
        ],
        "outboundTag": "api"
      },
      {
        "type": "field",
        "port": "443",
        "network": "udp",
        "outboundTag": "block"
      },
      {
        "type": "field",
        "outboundTag": "proxy",
        "domain": [
          "geosite:google"
        ]
      },
      {
        "type": "field",
        "outboundTag": "direct",
        "ip": [
          "geoip:private"
        ]
      },
      {
        "type": "field",
        "outboundTag": "direct",
        "domain": [
          "geosite:private"
        ]
      },
      {
        "type": "field",
        "outboundTag": "direct",
        "ip": [
          "223.5.5.5",
          "223.6.6.6",
          "2400:3200::1",
          "2400:3200:baba::1",
          "119.29.29.29",
          "1.12.12.12",
          "120.53.53.53",
          "2402:4e00::",
          "2402:4e00:1::",
          "180.76.76.76",
          "2400:da00::6666",
          "114.114.114.114",
          "114.114.115.115",
          "114.114.114.119",
          "114.114.115.119",
          "114.114.114.110",
          "114.114.115.110",
          "180.184.1.1",
          "180.184.2.2",
          "101.226.4.6",
          "218.30.118.6",
          "123.125.81.6",
          "140.207.198.6",
          "1.2.4.8",
          "210.2.4.8",
          "52.80.66.66",
          "117.50.22.22",
          "2400:7fc0:849e:200::4",
          "2404:c2c0:85d8:901::4",
          "117.50.10.10",
          "52.80.52.52",
          "2400:7fc0:849e:200::8",
          "2404:c2c0:85d8:901::8",
          "117.50.60.30",
          "52.80.60.30"
        ]
      },
      {
        "type": "field",
        "outboundTag": "direct",
        "domain": [
          "domain:alidns.com",
          "domain:doh.pub",
          "domain:dot.pub",
          "domain:360.cn",
          "domain:onedns.net"
        ]
      },
      {
        "type": "field",
        "outboundTag": "direct",
        "ip": [
          "geoip:cn"
        ]
      },
      {
        "type": "field",
        "outboundTag": "direct",
        "domain": [
          "geosite:cn"
        ]
      },
      {
        "type": "field",
        "inboundTag": [
          "direct-dns-1",
          "direct-dns-2"
        ],
        "outboundTag": "direct"
      },
      {
        "type": "field",
        "inboundTag": [
          "dns-module"
        ],
        "outboundTag": "proxy"
      }
    ]
  }
        """.trimIndent()
        return JSONObject.parseObject(rules)
    }

}
