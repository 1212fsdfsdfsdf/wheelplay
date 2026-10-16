package com.shilapi.xcertplay.web

import android.content.Context
import android.util.Log
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Publishes the Web listener on each reachable LAN interface. */
internal class WebMdns(private val context: Context) : AutoCloseable {
    @Volatile private var active = true
    private val instances = mutableMapOf<String, JmDNS>()
    private val worker = Thread({
        while (active) {
            refresh()
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
        }
    }, "wheelplay-web-mdns").apply { isDaemon = true; start() }

    @Synchronized private fun refresh() {
        if (!active) return
        val hostname = WebListenSettings.mdnsHostname(context)
        val port = WebSession.httpPort
        val addresses = LanAddresses.discover(context).filter {
            it.kind !in setOf(LanAddresses.Kind.TESLA_HTTP, LanAddresses.Kind.VPN,
                LanAddresses.Kind.CELLULAR, LanAddresses.Kind.P2P)
        }.associate { it.interfaceName + "/" + it.address to it.address }
        val target = addresses.keys.map { "$it/$hostname/$port" }.toSet()
        instances.keys.filter { it !in target }.forEach { key ->
            runCatching { instances.remove(key)?.close() }
        }
        for ((key, address) in addresses) {
            val identity = "$key/$hostname/$port"
            if (identity in instances) continue
            runCatching {
                val dns = JmDNS.create(InetAddress.getByName(address), hostname.removeSuffix(".local"))
                try {
                    dns.registerService(ServiceInfo.create("_http._tcp.local.", "WheelPlay", port, "path=/"))
                    instances[identity] = dns
                } catch (error: Exception) {
                    dns.close()
                    throw error
                }
            }.onFailure { Log.w("WebMdns", "mDNS publication failed on $key", it) }
        }
    }

    @Synchronized override fun close() {
        active = false
        worker.interrupt()
        instances.values.forEach { runCatching { it.close() } }
        instances.clear()
    }
}
