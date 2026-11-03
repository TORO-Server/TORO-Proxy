package net.torosaba.proxy

import org.yaml.snakeyaml.Yaml
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class HostPingConfig(
    val description: List<String> = emptyList(),
    val versionName: String? = null,
    val versionProtocol: String = "client",
    val favicon: List<String> = emptyList(),
    val playersOnline: String? = null,
    val playersMax: Int? = null,
    val playersHover: List<String> = emptyList(),
)

data class PingConfig(
    val hosts: Map<String, HostPingConfig>
) {
    fun findHostConfig(host: String): HostPingConfig {
        val cleanHost = host.removeSuffix(".").lowercase()
        // 1. 完全一致
        hosts[cleanHost]?.let { return it }

        // 2. ホスト名ごとのサブドメインまたは完全一致
        // より具体的な（ドメイン階層が深い）ものを優先
        val matched = hosts.entries
            .filter { (key, _) ->
                val k = key.removeSuffix(".").lowercase()
                k != "default" && (cleanHost == k || cleanHost.endsWith(".$k"))
            }
            .maxByOrNull { it.key.length }

        if (matched != null) return matched.value

        // 3. デフォルト
        return hosts["default"] ?: HostPingConfig()
    }

    companion object {
        private const val DEFAULT_CONFIG_FILE = "ping.yml"

        @Suppress("UNCHECKED_CAST")
        fun load(dataDir: Path): PingConfig {
            Files.createDirectories(dataDir)
            val file = dataDir.resolve(DEFAULT_CONFIG_FILE)
            if (Files.notExists(file)) {
                val defaultContent = loadDefaultTemplate()
                Files.writeString(file, defaultContent, StandardCharsets.UTF_8)
            }

            val yaml = Yaml()
            val root: Map<String, Any?> = Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                yaml.load(reader) as? Map<String, Any?> ?: emptyMap()
            }

            val hostsMap = mutableMapOf<String, HostPingConfig>()
            val hostsSection = root["hosts"] as? Map<String, Any?> ?: emptyMap()

            for ((hostKey, rawVal) in hostsSection) {
                if (rawVal !is Map<*, *>) continue
                val map = rawVal as Map<String, Any?>

                val descriptionList = parseStringList(map["description"])

                val versionSection = map["version"] as? Map<String, Any?>
                val versionName = versionSection?.get("name")?.toString()
                val versionProtocol = versionSection?.get("protocol")?.toString() ?: "client"

                val faviconList = parseStringList(map["favicon"])

                val playersSection = map["players"] as? Map<String, Any?>
                val playersOnline = playersSection?.get("online")?.toString()
                val playersMax = (playersSection?.get("max") as? Number)?.toInt()
                val playersHover = parseStringList(playersSection?.get("hover"))

                hostsMap[hostKey.lowercase()] = HostPingConfig(
                    description = descriptionList,
                    versionName = versionName,
                    versionProtocol = versionProtocol,
                    favicon = faviconList,
                    playersOnline = playersOnline,
                    playersMax = playersMax,
                    playersHover = playersHover
                )
            }

            return PingConfig(hostsMap)
        }

        private fun parseStringList(raw: Any?): List<String> {
            return when (raw) {
                is List<*> -> raw.mapNotNull { it?.toString() }
                is String -> listOf(raw)
                null -> emptyList()
                else -> listOf(raw.toString())
            }
        }

        private fun loadDefaultTemplate(): String {
            val resource = PingConfig::class.java.classLoader.getResourceAsStream("ping-default.yml")
            if (resource != null) {
                return InputStreamReader(resource, StandardCharsets.UTF_8).use { it.readText() }
            }
            return "# TORO-Proxy ping config\nhosts:\n  default:\n    description:\n      - \"&aTORO Server\"\n"
        }
    }
}
