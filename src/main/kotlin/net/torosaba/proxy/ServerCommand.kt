package net.torosaba.proxy

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * Velocity 組み込みの /server コマンドの代替。
 * limbo など待機用の隠しサーバーを一覧・タブ補完・接続先から除外する。
 */
class ServerCommand(
    private val proxy: ProxyServer,
    private val config: ProxyConfig,
) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        if (source !is Player) {
            source.sendMessage(Component.text("このコマンドはプレイヤーのみ実行できます。", NamedTextColor.RED))
            return
        }

        val availableServers = proxy.allServers
            .map { it.serverInfo.name }
            .filter { !it.equals(config.limboServer, ignoreCase = true) }

        if (args.isEmpty()) {
            val current = source.currentServer.map { it.serverInfo.name }.orElse("なし")
            source.sendMessage(Component.text("現在接続中のサーバー: $current", NamedTextColor.YELLOW))
            source.sendMessage(Component.text("接続可能なサーバー: " + availableServers.joinToString(", "), NamedTextColor.GOLD))
            return
        }

        val targetName = args[0]
        if (targetName.equals(config.limboServer, ignoreCase = true)) {
            source.sendMessage(Component.text("サーバーが見つかりません: $targetName", NamedTextColor.RED))
            return
        }

        val target = proxy.getServer(targetName).orElse(null)
        if (target == null) {
            source.sendMessage(Component.text("サーバーが見つかりません: $targetName", NamedTextColor.RED))
            return
        }

        val currentServer = source.currentServer.orElse(null)
        if (currentServer != null && currentServer.serverInfo.name.equals(targetName, ignoreCase = true)) {
            source.sendMessage(Component.text("既に $targetName に接続しています。", NamedTextColor.RED))
            return
        }

        source.sendMessage(Component.text("$targetName に接続しています...", NamedTextColor.GREEN))
        source.createConnectionRequest(target).connect().whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccessful) {
                val reason = result?.reasonComponent?.orElse(null)
                if (reason != null) {
                    source.sendMessage(reason)
                } else {
                    source.sendMessage(Component.text("$targetName への接続に失敗しました。", NamedTextColor.RED))
                }
            }
        }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
        return invocation.source().hasPermission("velocity.command.server")
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0].lowercase()
            return proxy.allServers
                .map { it.serverInfo.name }
                .filter { !it.equals(config.limboServer, ignoreCase = true) }
                .filter { it.lowercase().startsWith(prefix) }
        }
        return emptyList()
    }
}
