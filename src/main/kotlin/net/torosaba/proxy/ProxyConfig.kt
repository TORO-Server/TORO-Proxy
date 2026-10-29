package net.torosaba.proxy

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** plugins/toro-proxy/config.properties の中身。無ければ既定の値で作る */
data class ProxyConfig(
    val mainServers: List<String>,
    val limboServer: String,
    val restartKickMessages: Set<String>,
    val checkIntervalSeconds: Long,
    val returnDelaySeconds: Long,
    val returnBatchSize: Int,
    val maxReturnFailures: Int,
    val pingTimeoutSeconds: Long,
    val movedMessage: String,
    val returningMessage: String,
) {
    fun moved(): Component = Component.text(movedMessage, NamedTextColor.YELLOW)
    fun returning(): Component = Component.text(returningMessage, NamedTextColor.GREEN)

    companion object {
        private const val DEFAULTS = """# TORO-Proxy の設定。変えたら Velocity を再起動する
# 再起動のときに limbo で待たせる対象のサーバー (velocity.toml の [servers] の名前。| で区切る)
main-servers=toro
# 待たせる先のサーバー
limbo-server=limbo
# このメッセージでキックされたときは再起動とみなす (Paper の bukkit.yml の shutdown-message と spigot.yml の messages.restart)
# 理由なしで切れたとき (落ちたとき・繋がらないとき) も再起動とみなす。それ以外のキック (BAN・/kick・満員) はそのまま切断する
restart-kick-messages=Server closed|Server is restarting
# 対象のサーバーが起動したかを確かめる間隔 (秒)
check-interval-seconds=3
# 起動を確かめてから戻し始めるまでの待ち (秒)
return-delay-seconds=3
# 一度に戻す人数 (間隔ごと)
return-batch-size=5
# 戻そうとして再起動以外の理由で断られたとき、何回で諦めて切断するか
max-return-failures=3
ping-timeout-seconds=3
message-moved=サーバーを再起動しています。起動したら自動で戻ります。
message-returning=サーバーが起動しました。戻ります。
"""

        fun load(dir: Path): ProxyConfig {
            Files.createDirectories(dir)
            val file = dir.resolve("config.properties")
            if (Files.notExists(file)) Files.writeString(file, DEFAULTS)
            val props = Properties().apply { load(DEFAULTS.reader()) }
            Files.newBufferedReader(file).use { props.load(it) }
            return from(props)
        }

        fun from(props: Properties): ProxyConfig {
            fun str(key: String) = props.getProperty(key)?.trim().orEmpty()
            fun list(key: String) = str(key).split('|').map { it.trim() }.filter { it.isNotEmpty() }
            fun long(key: String, min: Long) = (str(key).toLongOrNull() ?: min).coerceAtLeast(min)
            val mains = list("main-servers")
            require(mains.isNotEmpty()) { "main-servers が空です" }
            return ProxyConfig(
                mainServers = mains,
                limboServer = str("limbo-server").ifEmpty { "limbo" },
                restartKickMessages = list("restart-kick-messages").toSet(),
                checkIntervalSeconds = long("check-interval-seconds", 1),
                returnDelaySeconds = long("return-delay-seconds", 0),
                returnBatchSize = long("return-batch-size", 1).toInt(),
                maxReturnFailures = long("max-return-failures", 1).toInt(),
                pingTimeoutSeconds = long("ping-timeout-seconds", 1),
                movedMessage = str("message-moved"),
                returningMessage = str("message-returning"),
            )
        }
    }
}
