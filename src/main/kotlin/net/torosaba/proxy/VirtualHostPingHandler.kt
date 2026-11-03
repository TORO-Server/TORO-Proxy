package net.torosaba.proxy

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerPing
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class VirtualHostPingHandler(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val dataDir: Path,
) {
    private var pingConfig: PingConfig = PingConfig.load(dataDir)
    private val faviconManager: FaviconManager = FaviconManager(dataDir, logger)

    fun reload() {
        pingConfig = PingConfig.load(dataDir)
        faviconManager.clearCache()
        logger.info("サーバーリスト (ping.yml) の設定を再読み込みしました")
    }

    @Subscribe
    fun onProxyPing(event: ProxyPingEvent) {
        val virtualHost = event.connection.virtualHost.orElse(null)
        val host = virtualHost?.hostString?.removeSuffix(".")?.lowercase() ?: "default"

        val hostConfig = pingConfig.findHostConfig(host)
        val original = event.ping
        val builder = original.asBuilder()

        // 1. Version
        hostConfig.versionName?.let { versionName ->
            val protocol = when (hostConfig.versionProtocol.lowercase()) {
                "keep", "original" -> original.version.protocol
                "client" -> event.connection.protocolVersion.protocol
                else -> hostConfig.versionProtocol.toIntOrNull() ?: -1
            }
            builder.version(ServerPing.Version(protocol, versionName))
        }

        // 2. Players (Online & Max)
        val onlineCount = parseOnline(hostConfig.playersOnline) ?: original.players.map { it.online }.orElse(proxy.playerCount)
        val maxCount = hostConfig.playersMax ?: original.players.map { it.max }.orElse(100)
        builder.onlinePlayers(onlineCount)
        builder.maximumPlayers(maxCount)

        val placeholders = mapOf(
            "%online%" to onlineCount.toString(),
            "%max%" to maxCount.toString(),
            "%player%" to ""
        )

        // 3. Hover (SamplePlayers)
        if (hostConfig.playersHover.isNotEmpty()) {
            val sampleLines = mutableListOf<ServerPing.SamplePlayer>()
            for (hoverEntry in hostConfig.playersHover) {
                val lines = hoverEntry.split('\n')
                for (line in lines) {
                    val comp = PingFormatter.format(line, placeholders)
                    val legacyText = LegacyComponentSerializer.legacySection().serialize(comp)
                    sampleLines.add(ServerPing.SamplePlayer(legacyText, UUID.randomUUID()))
                }
            }
            builder.samplePlayers(*sampleLines.toTypedArray())
        }

        // 4. Description (MOTD)
        if (hostConfig.description.isNotEmpty()) {
            val chosen = if (hostConfig.description.size == 1) {
                hostConfig.description[0]
            } else {
                hostConfig.description[ThreadLocalRandom.current().nextInt(hostConfig.description.size)]
            }
            val comp = PingFormatter.format(chosen, placeholders)
            builder.description(comp)
        }

        // 5. Favicon
        if (hostConfig.favicon.isNotEmpty()) {
            val fav = faviconManager.getFavicon(hostConfig.favicon)
            if (fav != null) {
                builder.favicon(fav)
            }
        }

        event.ping = builder.build()
    }

    private fun parseOnline(spec: String?): Int? {
        if (spec.isNullOrBlank()) return null
        val range = spec.split("..")
        if (range.size == 2) {
            val min = range[0].trim().toIntOrNull()
            val max = range[1].trim().toIntOrNull()
            if (min != null && max != null && min <= max) {
                return ThreadLocalRandom.current().nextInt(min, max + 1)
            }
        }
        return spec.trim().toIntOrNull()
    }
}
