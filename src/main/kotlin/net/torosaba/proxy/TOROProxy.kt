package net.torosaba.proxy

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
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
