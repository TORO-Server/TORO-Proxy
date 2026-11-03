package net.torosaba.proxy

import com.velocitypowered.api.util.Favicon
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

class FaviconManager(
    private val dataDir: Path,
    private val logger: Logger
) {
    private val cache = ConcurrentHashMap<String, Favicon>()
    private val iconsDir: Path = dataDir.resolve("icons")

    init {
        Files.createDirectories(iconsDir)
    }

    fun getFavicon(filenames: List<String>): Favicon? {
        if (filenames.isEmpty()) return null
        val chosen = if (filenames.size == 1) filenames[0] else filenames[ThreadLocalRandom.current().nextInt(filenames.size)]
        return getOrLoad(chosen)
    }

    private fun getOrLoad(filename: String): Favicon? {
        val cached = cache[filename]
        if (cached != null) return cached

        // 1. dataDir/icons/<filename> を探す
        var targetPath = iconsDir.resolve(filename)
        if (Files.notExists(targetPath)) {
            // 2. dataDir/<filename> も探す
            val inDataDir = dataDir.resolve(filename)
            if (Files.exists(inDataDir)) {
                targetPath = inDataDir
            } else {
                // 3. 内蔵リソースからコピーを試みる
                copyFromResource(filename, targetPath)
            }
        }

        if (Files.exists(targetPath)) {
            return try {
                val fav = Favicon.create(targetPath)
                cache[filename] = fav
                fav
            } catch (e: Exception) {
                logger.warn("アイコン {} の読み込みに失敗しました: {}", targetPath, e.message)
                null
            }
        }
        return null
    }

    private fun copyFromResource(filename: String, dest: Path) {
        val classLoader = javaClass.classLoader
        val stream = classLoader.getResourceAsStream("icons/$filename")
            ?: classLoader.getResourceAsStream(filename)
            ?: return

        try {
            Files.createDirectories(dest.parent ?: iconsDir)
            stream.use { input ->
                Files.copy(input, dest)
            }
            logger.info("内蔵アイコン {} を {} に配置しました", filename, dest)
        } catch (e: Exception) {
            logger.warn("内蔵アイコン {} のコピーに失敗しました: {}", filename, e.message)
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
