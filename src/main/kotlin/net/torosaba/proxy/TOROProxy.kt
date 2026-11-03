package net.torosaba.proxy

import com.google.inject.Inject
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.slf4j.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.TimeUnit

class TOROProxy @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDir: Path,
) {
    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val config = ProxyConfig.load(dataDir)
        // サーバー側のスクリプトが「誰もいないときに再起動する」判断に使う。統合版 (Geyser) の人は TCP の接続数に出ないため
        proxy.scheduler.buildTask(this, Runnable { writeOnline() }).repeat(10, TimeUnit.SECONDS).schedule()
        if (config.clientsEndpoint.isNotEmpty() && config.clientsToken.isNotEmpty()) {
            val reporter = ClientReporter(proxy, logger, config, this)
            proxy.eventManager.register(this, reporter)
            reporter.start()
            logger.info("いまつながっているプレイヤーの IP を {} に知らせます", config.clientsEndpoint)
        }
        // built-in の /server を解除し、limbo を隠したカスタム /server を登録
        proxy.commandManager.unregister("server")
        val serverMeta = proxy.commandManager.metaBuilder("server").plugin(this).build()
        proxy.commandManager.register(serverMeta, ServerCommand(proxy, config))

        // ドメイン別 Ping (MOTD・Favicon・バージョン表示) ハンドラ (ServerListPlus 完全代替)
        val pingHandler = VirtualHostPingHandler(proxy, logger, dataDir)
        proxy.eventManager.register(this, pingHandler)

        // /toroproxy コマンド (設定リロード等)
        val toroProxyCmd = object : SimpleCommand {
            override fun execute(invocation: SimpleCommand.Invocation) {
                val source = invocation.source()
                val args = invocation.arguments()
                if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
                    pingHandler.reload()
                    source.sendMessage(Component.text("[TORO-Proxy] ping.yml を再読み込みしました。", NamedTextColor.GREEN))
                } else {
                    source.sendMessage(Component.text("使用法: /toroproxy reload", NamedTextColor.YELLOW))
                }
            }

            override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
                return invocation.source().hasPermission("toroproxy.admin")
            }
        }
        val toroProxyMeta = proxy.commandManager.metaBuilder("toroproxy").plugin(this).build()
        proxy.commandManager.register(toroProxyMeta, toroProxyCmd)

        val missing = (config.mainServers + config.limboServer).filter { proxy.getServer(it).isEmpty }
        if (missing.isNotEmpty()) {
            logger.warn("velocity.toml に無いサーバーがあります: {}。limbo での待機は動きません", missing)
            return
        }
        val relay = LimboRelay(proxy, logger, config)
        proxy.eventManager.register(this, relay)
        proxy.scheduler.buildTask(this, Runnable { relay.tick() })
            .repeat(config.checkIntervalSeconds, TimeUnit.SECONDS)
            .schedule()
        logger.info("{} の再起動中は {} で待たせます", config.mainServers, config.limboServer)
    }

    /** plugins/toro-proxy/online.txt に「人数 書いた時刻(エポック秒)」を書く */
    private fun writeOnline() {
        try {
            val tmp = dataDir.resolve("online.txt.tmp")
            Files.writeString(tmp, "${proxy.playerCount} ${Instant.now().epochSecond}\n")
            Files.move(tmp, dataDir.resolve("online.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            logger.warn("online.txt を書けませんでした: {}", e.message)
        }
    }
}
