package ai.arena.happish.core

import ai.arena.happish.data.AppLog
import ai.arena.happish.data.AppStorage
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.security.KeyStore
import android.util.Base64

/**
 * Runtime adapter for the real sing-box Android core (`io.nekohasekai.libbox`).
 *
 * Why reflection instead of direct imports?
 * - The project can still open/build its UI without `app/libs/libbox.aar`.
 * - As soon as `libbox.aar` is placed in `app/libs`, this adapter uses the real classes:
 *   CommandServer, CommandServerHandler, PlatformInterface, OverrideOptions, TunOptions, etc.
 *
 * Tested API target: modern sing-box/libbox builds expose:
 * - io.nekohasekai.libbox.CommandServer(handler, platformInterface)
 * - commandServer.start()
 * - commandServer.startOrReloadService(configJson, OverrideOptions())
 * - commandServer.closeService()/close()
 * - PlatformInterface.openTun(TunOptions): int
 */
class SingBoxCoreAdapter(
    private val service: HappishVpnService,
    private val configContent: String
) {
    private var commandServer: Any? = null
    private var tun: ParcelFileDescriptor? = null

    fun isAvailable(): Boolean = runCatching {
        Class.forName("io.nekohasekai.libbox.CommandServer")
        Class.forName("io.nekohasekai.libbox.PlatformInterface")
        true
    }.getOrDefault(false)

    fun start() {
        if (!isAvailable()) {
            error(
                "libbox.aar не найден. Положи sing-box libbox.aar в app/libs/libbox.aar " +
                    "и пересобери приложение."
            )
        }

        val platformInterfaceClass = Class.forName("io.nekohasekai.libbox.PlatformInterface")
        val handlerClass = Class.forName("io.nekohasekai.libbox.CommandServerHandler")
        val commandServerClass = Class.forName("io.nekohasekai.libbox.CommandServer")
        val overrideOptionsClass = Class.forName("io.nekohasekai.libbox.OverrideOptions")

        val platformProxy = Proxy.newProxyInstance(
            platformInterfaceClass.classLoader,
            arrayOf(platformInterfaceClass),
            PlatformInvocationHandler()
        )
        val handlerProxy = Proxy.newProxyInstance(
            handlerClass.classLoader,
            arrayOf(handlerClass),
            CommandServerHandlerInvocationHandler()
        )

        val server = commandServerClass
            .constructors
            .firstOrNull { ctor ->
                val types = ctor.parameterTypes
                types.size == 2 && types[0].isAssignableFrom(handlerClass) && types[1].isAssignableFrom(platformInterfaceClass)
            }
            ?.newInstance(handlerProxy, platformProxy)
            ?: commandServerClass.getConstructor(handlerClass, platformInterfaceClass)
                .newInstance(handlerProxy, platformProxy)

        commandServer = server
        AppLog.i("CommandServer создан")
        server.callNoArg("start")

        val savedSettings = AppStorage(service).load()
        val overrideOptions = overrideOptionsClass.getDeclaredConstructor().newInstance().apply {
            setBoolIfExists("autoRedirect", false)
            if (savedSettings.splitTunneling && savedSettings.splitTunnelPackages.isNotEmpty()) {
                if (savedSettings.splitTunnelIncludeMode) {
                    val include = savedSettings.splitTunnelPackages + service.packageName
                    setIfExists("includePackage", stringIterator(include.toList()))
                    AppLog.i("Split tunneling: через VPN только ${savedSettings.splitTunnelPackages.size} выбранных приложений")
                } else {
                    val exclude = savedSettings.splitTunnelPackages - service.packageName
                    setIfExists("excludePackage", stringIterator(exclude.toList()))
                    AppLog.i("Split tunneling: ${exclude.size} выбранных приложений мимо VPN")
                }
            }
        }

        val startOrReload = server.javaClass.methods.firstOrNull {
            it.name == "startOrReloadService" && it.parameterTypes.size == 2
        } ?: error("libbox CommandServer.startOrReloadService(String, OverrideOptions) not found")

        startOrReload.invoke(server, configContent, overrideOptions)
        Log.i(TAG, "sing-box/libbox started with generated config")
        AppLog.i("sing-box service запущен")
    }

    fun stop() {
        AppLog.i("Остановка sing-box core")
        val server = commandServer
        if (server != null) {
            runCatching { server.callNoArg("closeService") }
                .onFailure { Log.w(TAG, "closeService failed: ${it.message}") }
            runCatching { server.callNoArg("close") }
                .onFailure { Log.w(TAG, "close failed: ${it.message}") }
        }
        commandServer = null
        closeTun()
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    private inner class CommandServerHandlerInvocationHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "serviceStop" -> {
                    Log.i(TAG, "libbox requested serviceStop")
                    AppLog.w("libbox запросил остановку сервиса")
                    closeTun()
                    service.stopSelfFromCore()
                    null
                }
                "serviceReload" -> {
                    Log.i(TAG, "libbox requested serviceReload")
                    AppLog.i("libbox запросил reload сервиса")
                    null
                }
                "getSystemProxyStatus" -> newSystemProxyStatus(false, false)
                "setSystemProxyEnabled" -> null
                "writeDebugMessage" -> {
                    val message = args?.firstOrNull()?.toString().orEmpty()
                    Log.d("sing-box", message)
                    if (message.isNotBlank()) AppLog.i("sing-box: $message")
                    null
                }
                "toString" -> "HappishCommandServerHandler"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturn(method.returnType)
            }
        }
    }

    private inner class PlatformInvocationHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = (args?.firstOrNull() as? Number)?.toInt()
                    if (fd != null) service.protect(fd)
                    null
                }
                "openTun" -> openTun(args?.firstOrNull() ?: error("TunOptions missing"))
                "useProcFS" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                "findConnectionOwner" -> error("android: connection owner lookup is not implemented in MVP")
                "startDefaultInterfaceMonitor" -> null
                "closeDefaultInterfaceMonitor" -> null
                "getInterfaces" -> networkInterfaceIterator()
                "underNetworkExtension" -> false
                "includeAllNetworks" -> false
                "clearDNSCache" -> null
                "readWIFIState" -> null
                "localDNSTransport" -> null
                "systemCertificates" -> stringIterator(systemCertificates())
                "sendNotification" -> {
                    Log.i(TAG, "libbox notification: ${args?.firstOrNull()}")
                    null
                }
                "toString" -> "HappishPlatformInterface"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturn(method.returnType)
            }
        }
    }

    private fun openTun(options: Any): Int {
        if (VpnService.prepare(service) != null) error("android: missing vpn permission")

        val mtu = options.intGetter("mtu", "getMtu") ?: 1500
        val autoRoute = options.boolGetter("autoRoute", "getAutoRoute", "isAutoRoute") ?: true

        val builder = service.newVpnBuilder()
            .setSession("Happish VPN")
            .setMtu(mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        addAddresses(builder, options.objGetter("inet4Address", "getInet4Address"))
        addAddresses(builder, options.objGetter("inet6Address", "getInet6Address"))

        if (autoRoute) {
            val dns = options.objGetter("dnsServerAddress", "getDnsServerAddress")
                ?.stringGetter("value", "getValue")
                ?.takeIf { it.isNotBlank() }
            if (dns != null) runCatching { builder.addDnsServer(dns) }
            else runCatching { builder.addDnsServer("1.1.1.1") }

            val routeAdded = addRoutes(builder, options.objGetter("inet4RouteAddress", "getInet4RouteAddress")) or
                addRoutes(builder, options.objGetter("inet4RouteRange", "getInet4RouteRange"))
            val route6Added = addRoutes(builder, options.objGetter("inet6RouteAddress", "getInet6RouteAddress")) or
                addRoutes(builder, options.objGetter("inet6RouteRange", "getInet6RouteRange"))

            if (!routeAdded) runCatching { builder.addRoute("0.0.0.0", 0) }
            if (!route6Added) runCatching { builder.addRoute("::", 0) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                excludeRoutes(builder, options.objGetter("inet4RouteExcludeAddress", "getInet4RouteExcludeAddress"))
                excludeRoutes(builder, options.objGetter("inet6RouteExcludeAddress", "getInet6RouteExcludeAddress"))
            }

            addPackages(builder, options.objGetter("includePackage", "getIncludePackage"), include = true)
            addPackages(builder, options.objGetter("excludePackage", "getExcludePackage"), include = false)
        }

        val httpProxyEnabled = options.boolGetter("isHTTPProxyEnabled", "getIsHTTPProxyEnabled", "isHttpProxyEnabled") ?: false
        if (httpProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val host = options.stringGetter("httpProxyServer", "getHttpProxyServer")
            val port = options.intGetter("httpProxyServerPort", "getHttpProxyServerPort") ?: 0
            if (!host.isNullOrBlank() && port > 0) {
                runCatching { builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port)) }
            }
        }

        val pfd = builder.establish() ?: error("android: VPN establish returned null")
        closeTun()
        tun = pfd
        Log.i(TAG, "TUN opened, fd=${pfd.fd}, mtu=$mtu")
        AppLog.i("TUN открыт: fd=${pfd.fd}, mtu=$mtu")
        return pfd.fd
    }

    private fun addAddresses(builder: VpnService.Builder, iterator: Any?) {
        iterator.forEachLibboxItem { item ->
            val address = item.stringGetter("address", "getAddress") ?: return@forEachLibboxItem
            val prefix = item.intGetter("prefix", "getPrefix") ?: return@forEachLibboxItem
            runCatching { builder.addAddress(address, prefix) }
                .onFailure { Log.w(TAG, "addAddress($address/$prefix) failed: ${it.message}") }
        }
    }

    private fun addRoutes(builder: VpnService.Builder, iterator: Any?): Boolean {
        var added = false
        iterator.forEachLibboxItem { item ->
            val address = item.stringGetter("address", "getAddress") ?: return@forEachLibboxItem
            val prefix = item.intGetter("prefix", "getPrefix") ?: return@forEachLibboxItem
            runCatching {
                builder.addRoute(address, prefix)
                added = true
            }.onFailure { Log.w(TAG, "addRoute($address/$prefix) failed: ${it.message}") }
        }
        return added
    }

    private fun excludeRoutes(builder: VpnService.Builder, iterator: Any?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        iterator.forEachLibboxItem { item ->
            val address = item.stringGetter("address", "getAddress") ?: return@forEachLibboxItem
            val prefix = item.intGetter("prefix", "getPrefix") ?: return@forEachLibboxItem
            runCatching { builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(address), prefix)) }
                .onFailure { Log.w(TAG, "excludeRoute($address/$prefix) failed: ${it.message}") }
        }
    }

    private fun addPackages(builder: VpnService.Builder, iterator: Any?, include: Boolean) {
        iterator.forEachLibboxItem { item ->
            val pkg = item.toString()
            if (pkg.isBlank()) return@forEachLibboxItem
            try {
                if (include) builder.addAllowedApplication(pkg) else builder.addDisallowedApplication(pkg)
            } catch (e: NameNotFoundException) {
                Log.w(TAG, "package not found for VPN rule: $pkg")
            }
        }
    }

    private fun networkInterfaceIterator(): Any {
        val iteratorClass = Class.forName("io.nekohasekai.libbox.NetworkInterfaceIterator")
        val itemClass = Class.forName("io.nekohasekai.libbox.NetworkInterface")
        val libboxClass = Class.forName("io.nekohasekai.libbox.Libbox")
        val stringIteratorClass = Class.forName("io.nekohasekai.libbox.StringIterator")

        val items = NetworkInterface.getNetworkInterfaces().toList().mapNotNull { ni ->
            runCatching {
                itemClass.getDeclaredConstructor().newInstance().apply {
                    setIfExists("name", ni.name)
                    setIfExists("index", ni.index.toLong())
                    setIfExists("mtu", ni.mtu.toLong())
                    setIfExists("addresses", stringIterator(ni.interfaceAddresses.mapNotNull { it.toPrefixOrNull() }))
                    setIfExists("dnsServer", stringIterator(emptyList()))
                    val type = runCatching { libboxClass.getField("InterfaceTypeOther").getLong(null) }.getOrDefault(0L)
                    setIfExists("type", type)
                    setIfExists("flags", 0L)
                    setIfExists("metered", false)
                }
            }.getOrNull()
        }
        val iterator = items.iterator()
        return Proxy.newProxyInstance(iteratorClass.classLoader, arrayOf(iteratorClass)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> iterator.hasNext()
                "next" -> iterator.next()
                "toString" -> "HappishNetworkInterfaceIterator"
                else -> defaultReturn(method.returnType)
            }
        }
    }

    private fun stringIterator(values: List<String>): Any {
        val stringIteratorClass = Class.forName("io.nekohasekai.libbox.StringIterator")
        val iterator = values.iterator()
        return Proxy.newProxyInstance(stringIteratorClass.classLoader, arrayOf(stringIteratorClass)) { _, method, _ ->
            when (method.name) {
                "len" -> values.size
                "hasNext" -> iterator.hasNext()
                "next" -> iterator.next()
                "toString" -> "HappishStringIterator"
                else -> defaultReturn(method.returnType)
            }
        }
    }

    private fun newSystemProxyStatus(available: Boolean, enabled: Boolean): Any? = runCatching {
        Class.forName("io.nekohasekai.libbox.SystemProxyStatus").getDeclaredConstructor().newInstance().apply {
            setIfExists("available", available)
            setIfExists("enabled", enabled)
        }
    }.getOrNull()

    private fun systemCertificates(): List<String> = runCatching {
        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null, null)
        val result = mutableListOf<String>()
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val cert = store.getCertificate(aliases.nextElement()) ?: continue
            result += "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP) +
                "\n-----END CERTIFICATE-----"
        }
        result
    }.getOrDefault(emptyList())

    private fun InterfaceAddress.toPrefixOrNull(): String? = runCatching {
        val host = if (address is Inet6Address) {
            Inet6Address.getByAddress(address.address).hostAddress
        } else {
            address.hostAddress
        }
        "$host/$networkPrefixLength"
    }.getOrNull()

    private fun Any?.forEachLibboxItem(block: (Any) -> Unit) {
        val iterator = this ?: return
        while (iterator.boolCall("hasNext") == true) {
            val item = iterator.callNoArgOrNull("next") ?: break
            block(item)
        }
    }

    private fun Any.callNoArg(name: String): Any? = javaClass.methods.first { it.name == name && it.parameterTypes.isEmpty() }.invoke(this)
    private fun Any.callNoArgOrNull(name: String): Any? = javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(this)
    private fun Any.boolCall(name: String): Boolean? = (callNoArgOrNull(name) as? Boolean)

    private fun Any.objGetter(vararg names: String): Any? = names.firstNotNullOfOrNull { name ->
        callNoArgOrNull(name) ?: runCatching { javaClass.fields.firstOrNull { it.name == name }?.get(this) }.getOrNull()
    }
    private fun Any.stringGetter(vararg names: String): String? = objGetter(*names)?.toString()
    private fun Any.intGetter(vararg names: String): Int? = when (val value = objGetter(*names)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
    private fun Any.boolGetter(vararg names: String): Boolean? = when (val value = objGetter(*names)) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    private fun Any.setBoolIfExists(name: String, value: Boolean) {
        setIfExists(name, value)
    }

    private fun Any.setIfExists(name: String, value: Any?) {
        val setter = "set" + name.replaceFirstChar { it.uppercase() }
        runCatching {
            val method = javaClass.methods.firstOrNull { it.name == setter && it.parameterTypes.size == 1 }
            if (method != null) {
                method.invoke(this, coerceForReflection(value, method.parameterTypes[0]))
                return@runCatching
            }
            val field = javaClass.fields.firstOrNull { it.name == name }
            if (field != null) field.set(this, coerceForReflection(value, field.type))
        }
    }

    private fun coerceForReflection(value: Any?, target: Class<*>): Any? {
        if (value !is Number) return value
        return when (target) {
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> value.toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> value.toLong()
            java.lang.Float.TYPE, java.lang.Float::class.java -> value.toFloat()
            java.lang.Double.TYPE, java.lang.Double::class.java -> value.toDouble()
            else -> value
        }
    }

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Void.TYPE -> null
        else -> null
    }

    companion object {
        private const val TAG = "SingBoxCoreAdapter"
    }
}
