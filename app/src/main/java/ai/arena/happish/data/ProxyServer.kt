package ai.arena.happish.data

import java.net.URLDecoder

data class ProxyServer(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val uuid: String? = null,
    val method: String? = null,
    val params: Map<String, String> = emptyMap(),
    val raw: String
) {
    val subtitle: String get() = "${protocol.uppercase()} • $host:$port"
}

fun decodeUrlPart(value: String?): String = runCatching {
    URLDecoder.decode(value.orEmpty(), "UTF-8")
}.getOrDefault(value.orEmpty())
