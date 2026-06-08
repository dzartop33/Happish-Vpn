package ai.arena.happish.core

import ai.arena.happish.data.ProxyServer
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigGenerator {
    fun generate(server: ProxyServer, dnsAddress: String = "tls://8.8.8.8", routeMode: String = "global"): String {
        val outbound = JSONObject()
            .put("type", outboundType(server.protocol))
            .put("tag", "proxy")
            .put("server", server.host)
            .put("server_port", server.port)

        when (server.protocol) {
            "vless" -> {
                outbound.put("uuid", server.uuid.orEmpty())
                addTlsAndTransport(outbound, server)
            }
            "vmess" -> {
                outbound.put("uuid", server.uuid.orEmpty())
                outbound.put("security", server.method ?: "auto")
                outbound.put("alter_id", server.params["alterId"]?.toIntOrNull() ?: 0)
                addTlsAndTransport(outbound, server)
            }
            "trojan" -> {
                outbound.put("password", server.password.orEmpty())
                addTlsAndTransport(outbound, server, forceTls = true)
            }
            "shadowsocks" -> {
                outbound.put("method", server.method ?: "2022-blake3-aes-128-gcm")
                outbound.put("password", server.password.orEmpty())
            }
            "hysteria2" -> {
                outbound.put("password", server.password.orEmpty())
                addTls(outbound, server, true)
            }
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put("dns", JSONObject()
                .put("servers", JSONArray().put(JSONObject().put("tag", "dns-main").put("address", dnsAddress.ifBlank { "tls://8.8.8.8" })))
                .put("final", "dns-main")
            )
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("interface_name", "tun0")
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("mtu", 9000)
                .put("auto_route", true)
                .put("strict_route", false)
                .put("stack", "system")
                .put("sniff", true)
            ))
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
                .put(JSONObject().put("type", "block").put("tag", "block"))
            )
            .put("route", buildRoute(routeMode))
            .toString(2)
    }

    private fun buildRoute(routeMode: String): JSONObject {
        val rules = JSONArray()
        val bypassLanRule = JSONObject()
            .put("ip_cidr", JSONArray()
                .put("10.0.0.0/8")
                .put("172.16.0.0/12")
                .put("192.168.0.0/16")
                .put("127.0.0.0/8")
                .put("169.254.0.0/16")
                .put("::1/128")
                .put("fc00::/7")
                .put("fe80::/10")
            )
            .put("outbound", "direct")

        when (routeMode) {
            "direct" -> Unit
            "rule", "bypass_lan" -> rules.put(bypassLanRule)
            else -> Unit
        }

        return JSONObject()
            .put("auto_detect_interface", true)
            .put("rules", rules)
            .put("final", if (routeMode == "direct") "direct" else "proxy")
    }

    private fun outboundType(protocol: String) = when (protocol) {
        "shadowsocks" -> "shadowsocks"
        "hysteria2" -> "hysteria2"
        else -> protocol
    }

    private fun addTlsAndTransport(outbound: JSONObject, server: ProxyServer, forceTls: Boolean = false) {
        val security = server.params["security"].orEmpty().lowercase()
        val tlsFlag = server.params["tls"].orEmpty().lowercase()
        val tlsEnabled = forceTls || security == "tls" || security == "reality" || tlsFlag == "tls" || tlsFlag == "1"
        addTls(outbound, server, tlsEnabled)
        addTransport(outbound, server)
    }

    private fun addTransport(outbound: JSONObject, server: ProxyServer) {
        val network = (server.params["type"] ?: server.params["network"] ?: "tcp").lowercase()
        val host = server.params["host"].orEmpty().ifBlank { server.params["authority"].orEmpty() }.ifBlank { server.host }
        val path = server.params["path"].orEmpty().ifBlank { "/" }
        val transport = when (network) {
            "ws", "websocket" -> JSONObject()
                .put("type", "ws")
                .put("path", path)
                .put("headers", JSONObject().put("Host", host))

            "grpc" -> JSONObject()
                .put("type", "grpc")
                .put("service_name", server.params["serviceName"] ?: server.params["service_name"] ?: "")

            "h2", "http" -> JSONObject()
                .put("type", "http")
                .put("host", JSONArray().put(host))
                .put("path", path)

            "httpupgrade", "http_upgrade" -> JSONObject()
                .put("type", "httpupgrade")
                .put("host", host)
                .put("path", path)

            "xhttp", "splithttp", "split-http" -> JSONObject()
                .put("type", "xhttp")
                .put("host", host)
                .put("path", path)
                .put("mode", server.params["mode"] ?: if (network == "splithttp" || network == "split-http") "packet-up" else "auto")

            else -> null
        }
        if (transport != null) outbound.put("transport", transport)
    }

    private fun addTls(outbound: JSONObject, server: ProxyServer, enabled: Boolean) {
        if (!enabled) return
        val security = server.params["security"].orEmpty().lowercase()
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", server.params["sni"] ?: server.params["peer"] ?: server.params["host"] ?: server.host)
            .put("insecure", server.params["allowInsecure"] == "1" || server.params["insecure"] == "1")

        val fingerprint = server.params["fp"] ?: server.params["fingerprint"]
        if (!fingerprint.isNullOrBlank()) {
            tls.put("utls", JSONObject()
                .put("enabled", true)
                .put("fingerprint", fingerprint)
            )
        }

        if (security == "reality") {
            tls.put("reality", JSONObject()
                .put("enabled", true)
                .put("public_key", server.params["pbk"] ?: server.params["publicKey"] ?: server.params["public_key"] ?: "")
                .put("short_id", server.params["sid"] ?: server.params["shortId"] ?: server.params["short_id"] ?: "")
            )
        }

        outbound.put("tls", tls)
    }
}
