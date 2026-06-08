package ai.arena.happish.data

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object SubscriptionParser {
    fun parseSubscription(text: String): List<ProxyServer> {
        val candidates = buildList {
            add(text.trim())
            decodeMaybeBase64(text.trim())?.let { add(it) }
        }
        return candidates
            .flatMap { body -> parseStructured(body) + body.lines().flatMap { splitPackedLine(it) }.mapNotNull { parseLink(it.trim()) } }
            .distinctBy { it.id }
    }

    private fun parseStructured(body: String): List<ProxyServer> = buildList {
        addAll(parseSingBoxJson(body))
        addAll(parseClashYaml(body))
    }

    private fun parseSingBoxJson(body: String): List<ProxyServer> = runCatching {
        val json = JSONObject(body)
        val outbounds = json.optJSONArray("outbounds") ?: return emptyList()
        buildList {
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                val type = outbound.optString("type")
                if (type !in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2")) continue
                val host = outbound.optString("server")
                val port = outbound.optInt("server_port", 0)
                if (host.isBlank() || port <= 0) continue
                val tls = outbound.optJSONObject("tls")
                val transport = outbound.optJSONObject("transport")
                val raw = outbound.toString()
                add(
                    ProxyServer(
                        id = stableId(raw),
                        name = outbound.optString("tag", host).ifBlank { host },
                        protocol = if (type == "shadowsocks") "shadowsocks" else type,
                        host = host,
                        port = port,
                        uuid = outbound.optString("uuid").takeIf { it.isNotBlank() },
                        password = outbound.optString("password").takeIf { it.isNotBlank() },
                        method = outbound.optString("method", outbound.optString("security")).takeIf { it.isNotBlank() },
                        params = buildMap {
                            tls?.let {
                                put("security", if (it.optJSONObject("reality")?.optBoolean("enabled") == true) "reality" else "tls")
                                put("sni", it.optString("server_name"))
                                it.optJSONObject("utls")?.optString("fingerprint")?.takeIf { fp -> fp.isNotBlank() }?.let { fp -> put("fp", fp) }
                                it.optJSONObject("reality")?.let { reality ->
                                    put("pbk", reality.optString("public_key"))
                                    put("sid", reality.optString("short_id"))
                                }
                            }
                            transport?.let {
                                put("type", it.optString("type"))
                                put("path", it.optString("path"))
                                put("host", it.optString("host"))
                                put("serviceName", it.optString("service_name"))
                                put("mode", it.optString("mode"))
                            }
                        }.filterValues { it.isNotBlank() },
                        raw = raw
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun parseClashYaml(body: String): List<ProxyServer> {
        if (!body.contains("proxies:") && !body.contains("Proxy:")) return emptyList()
        val blocks = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        body.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("- ") && (trimmed.contains("name:") || current != null)) {
                current?.let { blocks += it }
                current = mutableListOf(trimmed.removePrefix("- ").trim())
            } else if (current != null && (line.startsWith("  ") || line.startsWith("    "))) {
                current?.add(trimmed)
            }
        }
        current?.let { blocks += it }
        return blocks.mapNotNull { parseClashBlock(it) }.distinctBy { it.id }
    }

    private fun parseClashBlock(lines: List<String>): ProxyServer? = runCatching {
        val flat = linkedMapOf<String, String>()
        var prefix: String? = null
        lines.forEach { line ->
            val clean = line.trim().trimEnd(',')
            if (clean.endsWith(":")) {
                prefix = clean.removeSuffix(":")
                return@forEach
            }
            val key = clean.substringBefore(':', "").trim()
            val value = clean.substringAfter(':', "").trim().trim('"', '\'')
            if (key.isBlank()) return@forEach
            val fullKey = prefix?.let { "$it.$key" } ?: key
            flat[fullKey] = value
            flat[key] = value
        }
        val type = flat["type"].orEmpty().lowercase()
        if (type !in setOf("vless", "vmess", "trojan", "ss", "shadowsocks", "hysteria2", "hy2")) return null
        val host = flat["server"].orEmpty()
        val port = flat["port"]?.toIntOrNull() ?: return null
        if (host.isBlank()) return null
        val protocol = when (type) {
            "ss" -> "shadowsocks"
            "hy2" -> "hysteria2"
            else -> type
        }
        val raw = lines.joinToString("\n")
        ProxyServer(
            id = stableId(raw),
            name = flat["name"].orEmpty().ifBlank { host },
            protocol = protocol,
            host = host,
            port = port,
            uuid = flat["uuid"],
            password = flat["password"],
            method = flat["cipher"] ?: flat["method"],
            params = buildMap {
                flat["network"]?.let { put("type", it) }
                flat["tls"]?.takeIf { it == "true" }?.let { put("security", "tls") }
                flat["reality-opts.public-key"]?.let { put("security", "reality"); put("pbk", it) }
                flat["reality-opts.short-id"]?.let { put("sid", it) }
                flat["servername"]?.let { put("sni", it) }
                flat["sni"]?.let { put("sni", it) }
                flat["client-fingerprint"]?.let { put("fp", it) }
                flat["ws-opts.path"]?.let { put("path", it) }
                flat["ws-opts.headers.Host"]?.let { put("host", it) }
                flat["grpc-opts.grpc-service-name"]?.let { put("serviceName", it) }
            }.filterValues { it.isNotBlank() },
            raw = raw
        )
    }.getOrNull()

    private fun splitPackedLine(line: String): List<String> {
        if (line.isBlank()) return emptyList()
        val prefixes = listOf("vmess://", "vless://", "trojan://", "ss://", "hysteria2://", "hy2://")
        val hits = prefixes.mapNotNull { p -> line.indexOf(p).takeIf { it >= 0 } }.sorted()
        if (hits.size <= 1) return listOf(line)
        return hits.mapIndexed { index, start ->
            val end = hits.getOrNull(index + 1) ?: line.length
            line.substring(start, end)
        }
    }

    fun parseLink(link: String): ProxyServer? = when {
        link.startsWith("vmess://", true) -> parseVmess(link)
        link.startsWith("vless://", true) -> parseUriLike(link, "vless")
        link.startsWith("trojan://", true) -> parseUriLike(link, "trojan")
        link.startsWith("ss://", true) -> parseShadowsocks(link)
        link.startsWith("hy2://", true) || link.startsWith("hysteria2://", true) -> parseUriLike(link, "hysteria2")
        else -> null
    }

    private fun parseVmess(link: String): ProxyServer? = runCatching {
        val payload = link.removePrefix("vmess://")
        val json = JSONObject(decodeMaybeBase64(payload) ?: return null)
        val host = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: return null
        ProxyServer(
            id = stableId(link),
            name = json.optString("ps", host).ifBlank { host },
            protocol = "vmess",
            host = host,
            port = port,
            uuid = json.optString("id"),
            method = json.optString("scy", "auto"),
            params = buildMap {
                put("network", json.optString("net", "tcp"))
                put("tls", json.optString("tls"))
                put("host", json.optString("host"))
                put("path", json.optString("path"))
                put("sni", json.optString("sni"))
                put("alterId", json.optString("aid", "0"))
            }.filterValues { it.isNotBlank() },
            raw = link
        )
    }.getOrNull()

    private fun parseUriLike(link: String, protocol: String): ProxyServer? = runCatching {
        val uri = Uri.parse(link)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: defaultPort(protocol)
        val userInfo = uri.encodedUserInfo.orEmpty()
        val secret = decodeUrlPart(userInfo.substringBefore('@'))
        ProxyServer(
            id = stableId(link),
            name = decodeUrlPart(uri.fragment).ifBlank { host },
            protocol = protocol,
            host = host,
            port = port,
            username = secret.takeIf { protocol == "trojan" || protocol == "hysteria2" },
            password = secret.takeIf { protocol == "trojan" || protocol == "hysteria2" },
            uuid = secret.takeIf { protocol == "vless" },
            params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() },
            raw = link
        )
    }.getOrNull()

    private fun parseShadowsocks(link: String): ProxyServer? = runCatching {
        val noScheme = link.removePrefix("ss://")
        val fragment = noScheme.substringAfter('#', "")
        val beforeHash = noScheme.substringBefore('#')
        val query = beforeHash.substringAfter('?', "")
        val main = beforeHash.substringBefore('?')

        val decodedMain = if ('@' in main) main else decodeMaybeBase64(main) ?: main
        val credentials = decodedMain.substringBefore('@')
        val endpoint = decodedMain.substringAfter('@')
        val method = decodeUrlPart(credentials.substringBefore(':'))
        val pass = decodeUrlPart(credentials.substringAfter(':'))
        val host = endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null

        ProxyServer(
            id = stableId(link),
            name = decodeUrlPart(fragment).ifBlank { host },
            protocol = "shadowsocks",
            host = host,
            port = port,
            password = pass,
            method = method,
            params = query.split('&').mapNotNull {
                val k = it.substringBefore('=', "")
                val v = it.substringAfter('=', "")
                if (k.isBlank()) null else k to decodeUrlPart(v)
            }.toMap(),
            raw = link
        )
    }.getOrNull()

    private fun decodeMaybeBase64(input: String): String? {
        val clean = input.trim().replace("\n", "")
        val variants = listOf(clean, clean.padEnd(clean.length + (4 - clean.length % 4) % 4, '='))
        return variants.firstNotNullOfOrNull { value ->
            runCatching { String(Base64.decode(value, Base64.DEFAULT or Base64.URL_SAFE), Charsets.UTF_8) }.getOrNull()
        }
    }

    private fun defaultPort(protocol: String) = when (protocol) {
        "trojan", "vless", "vmess" -> 443
        "hysteria2" -> 443
        else -> 1080
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
}
