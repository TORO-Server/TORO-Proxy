package net.torosaba.proxy

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerPing
import com.velocitypowered.api.util.Favicon
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

class VirtualHostPingHandler(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val config: ProxyConfig,
    private val dataDir: Path,
) {
    private var customFavicon: Favicon? = null

    init {
        loadFavicon()
    }

    private fun loadFavicon() {
        if (config.marumasaIconFile.isEmpty()) return
        val iconPath = dataDir.resolve(config.marumasaIconFile)
        try {
            if (Files.notExists(iconPath)) {
                val resourceStream = javaClass.classLoader.getResourceAsStream("server-icon-3.0.png")
                if (resourceStream != null) {
                    Files.createDirectories(iconPath.parent ?: dataDir)
                    resourceStream.use { input ->
                        Files.copy(input, iconPath)
                    }
                    logger.info("内蔵の 3.0式アイコンを {} に出力しました", iconPath)
                }
            }
            if (Files.exists(iconPath)) {
                customFavicon = Favicon.create(iconPath)
                logger.info("3.0式アイコン ({}) を読み込みました", iconPath)
            }
        } catch (e: Exception) {
            logger.warn("3.0式アイコンの読み込みに失敗しました: {}", e.message)
        }
    }

    @Subscribe
    fun onProxyPing(event: ProxyPingEvent) {
        val virtualHost = event.connection.virtualHost.orElse(null) ?: return
        val host = virtualHost.hostString.removeSuffix(".").lowercase()

        val matched = config.marumasaDomains.any { domain ->
            val d = domain.removeSuffix(".").lowercase()
            host == d || host.endsWith(".$d")
        }
        if (!matched) return

        val original = event.ping
        val builder = original.asBuilder()

        if (config.marumasaVersionName.isNotEmpty()) {
            val protocol = when (config.marumasaVersionProtocol.lowercase()) {
                "keep", "original" -> original.version.protocol
                "client" -> event.connection.protocolVersion.protocol
                else -> config.marumasaVersionProtocol.toIntOrNull() ?: -1
            }
            builder.version(ServerPing.Version(protocol, config.marumasaVersionName))
        }

        customFavicon?.let {
            builder.favicon(it)
        }

        event.ping = builder.build()
    }
}
