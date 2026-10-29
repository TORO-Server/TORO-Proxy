package net.torosaba.proxy

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.slf4j.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 対象のサーバーが再起動で止まったプレイヤーを limbo に移し、起動したら元のサーバーに戻す。
 * 再起動ではないキック (BAN・/kick・満員) は、limbo を挟まずにそのまま切断する
 */
class LimboRelay(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val config: ProxyConfig,
) {
    private val returnTo = ConcurrentHashMap<UUID, String>()
    private val failures = ConcurrentHashMap<UUID, Int>()
    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val upSince = ConcurrentHashMap<String, Long>()

    @Subscribe
    fun onKicked(event: KickedFromServerEvent) {
        val from = event.server.serverInfo.name
        if (from !in config.mainServers) return
        val limbo = proxy.getServer(config.limboServer).orElse(null) ?: return
        val player = event.player
        val reason = event.serverKickReason.orElse(null)
        val onLimbo = isOnLimbo(player)

        if (KickPolicy.isRestart(reason?.let(::plain), config.restartKickMessages)) {
            returnTo[player.uniqueId] = from
            event.result = if (onLimbo) {
                // limbo から戻そうとして、まだ起動しきっていなかった。そのまま待たせる
                KickedFromServerEvent.Notify.create(Component.empty())
            } else {
                KickedFromServerEvent.RedirectPlayer.create(limbo, config.moved())
            }
            return
        }

        val shown = reason ?: Component.text("Disconnected")
        if (onLimbo && countFailure(player) < config.maxReturnFailures) {
            event.result = KickedFromServerEvent.Notify.create(shown)
            return
        }
        // velocity.toml の try に limbo を入れたので、何もしないと BAN でも limbo に送られてしまう
        forget(player.uniqueId)
        event.result = KickedFromServerEvent.DisconnectPlayer.create(shown)
    }

    @Subscribe
    fun onConnected(event: ServerConnectedEvent) {
        val id = event.player.uniqueId
        if (event.server.serverInfo.name == config.limboServer) {
            // ログインしたときに対象のサーバーが止まっていて、try の順で limbo に入った人もここで拾う
            returnTo.putIfAbsent(id, config.mainServers.first())
        } else {
            returnTo.remove(id)
            failures.remove(id)
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        forget(event.player.uniqueId)
    }

    /** スケジューラから一定の間隔で呼ぶ。limbo にいる人の戻り先が起動していれば、少しずつ戻す */
    fun tick() {
        val limbo = proxy.getServer(config.limboServer).orElse(null) ?: return
        val waiting = limbo.playersConnected.filter { it.uniqueId !in inFlight }
        if (waiting.isEmpty()) {
            upSince.clear()
            return
        }
        val byTarget = waiting.groupBy { returnTo[it.uniqueId] ?: config.mainServers.first() }
        for ((name, players) in byTarget) {
            val target = proxy.getServer(name).orElse(null) ?: continue
            target.ping().orTimeout(config.pingTimeoutSeconds, TimeUnit.SECONDS).whenComplete { _, error ->
                if (error != null) {
                    upSince.remove(name)
                    return@whenComplete
                }
                val now = System.currentTimeMillis()
                val since = upSince.computeIfAbsent(name) { now }
                if (now - since < TimeUnit.SECONDS.toMillis(config.returnDelaySeconds)) return@whenComplete
                players.take(config.returnBatchSize).forEach { send(it, target) }
            }
        }
    }

    private fun send(player: Player, target: RegisteredServer) {
        if (!player.isActive || !inFlight.add(player.uniqueId)) return
        player.sendMessage(config.returning())
        player.createConnectionRequest(target).connect().whenComplete { result, error ->
            inFlight.remove(player.uniqueId)
            if (error != null || result == null || result.isSuccessful) return@whenComplete
            if (result.status != ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) return@whenComplete
            val reason = result.reasonComponent.orElse(null)
            if (KickPolicy.isRestart(reason?.let(::plain), config.restartKickMessages)) return@whenComplete
            val shown = reason ?: Component.text("Disconnected")
            if (countFailure(player) >= config.maxReturnFailures) {
                logger.info("{} を {} に戻せませんでした: {}", player.username, target.serverInfo.name, plain(shown))
                forget(player.uniqueId)
                player.disconnect(shown)
            } else {
                player.sendMessage(shown)
            }
        }
    }

    private fun isOnLimbo(player: Player): Boolean =
        player.currentServer.map { it.serverInfo.name == config.limboServer }.orElse(false)

    private fun countFailure(player: Player): Int = failures.merge(player.uniqueId, 1, Int::plus) ?: 1

    private fun forget(id: UUID) {
        returnTo.remove(id)
        failures.remove(id)
        inFlight.remove(id)
    }

    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
}
