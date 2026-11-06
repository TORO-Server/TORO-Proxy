package net.torosaba.proxy

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerPing
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * サーバーリストに返す protocol。
 * 古い形の ping (Minecraft 1.6 以前の形。minecraft.jp などの監視サイトが使う) の相手は LEGACY (-2)、版の分からない相手は UNKNOWN (-1) になる。
 * どちらも Minecraft の版ではないので、そのまま返すと「Protocol Version が不正」と扱われる。Velocity が対応する最新の版を返す
 */
internal fun listedProtocol(protocol: Int): Int =
    if (protocol > 0) protocol else ProtocolVersion.MAXIMUM_VERSION.protocol

/**
 * サーバーリストに返す protocol を、host の設定から決める。
 * 名前が無い host と "keep" / "original" では元の ping の protocol を、"client" では接続してきた相手の protocol を、
 * 数値の文字列ならその値を、解釈できない文字列なら -1 を返す (版でない値は listedProtocol が直す)
 */
internal fun resolveProtocol(versionName: String?, versionProtocol: String, originalProtocol: Int, clientProtocol: Int): Int =
    when {
        versionName == null -> originalProtocol
        else -> when (versionProtocol.lowercase()) {
            "keep", "original" -> originalProtocol
            "client" -> clientProtocol
            else -> versionProtocol.toIntOrNull() ?: -1
        }
    }

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

        // 1. Version (名前の設定がない host でも、版でない protocol は返さない)
        val protocol = resolveProtocol(
            hostConfig.versionName,
            hostConfig.versionProtocol,
            original.version.protocol,
            event.connection.protocolVersion.protocol,
        )
        builder.version(ServerPing.Version(listedProtocol(protocol), hostConfig.versionName ?: original.version.name))

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
        val faviconList = hostConfig.favicon.ifEmpty {
            pingConfig.findHostConfig("default").favicon
        }
        if (faviconList.isNotEmpty()) {
            val fav = faviconManager.getFavicon(faviconList)
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
            val min = range[0].trim().toLongOrNull()
            val max = range[1].trim().toLongOrNull()
            if (min != null && max != null && min <= max) {
                val rand = ThreadLocalRandom.current().nextLong(min, max + 1)
                return rand.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            }
        }
        return spec.trim().toIntOrNull()
    }
}
