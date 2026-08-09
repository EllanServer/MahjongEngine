package top.ellan.mahjong.build

import java.io.File
import java.security.MessageDigest

/** Packages checked-in CraftEngine configuration and resource-pack files into the plugin JAR. */
object CraftEngineBundleGenerator {
    fun writeCraftEngineBundle(
        configurationDir: File,
        resourcepackDir: File,
        attributionFile: File,
        outputDir: File,
        projectVersion: String,
    ) {
        require(configurationDir.isDirectory) {
            "CraftEngine configuration directory does not exist: $configurationDir"
        }
        require(resourcepackDir.resolve("assets").isDirectory) {
            "Resource-pack assets directory does not exist: $resourcepackDir"
        }
        require(attributionFile.isFile) { "Resource-pack attribution is missing" }

        val configurationFiles =
            configurationDir
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("yml", "yaml", "json") }
                .toList()
        require(configurationFiles.isNotEmpty()) { "No CraftEngine configuration files found" }
        verifyTileFactory(configurationFiles, resourcepackDir)

        val outputRoot = outputDir.resolve("craftengine").resolve("mahjongpaper")
        val outputConfiguration = outputRoot.resolve("configuration")
        val outputAssets = outputRoot.resolve("resourcepack").resolve("assets")
        outputRoot.deleteRecursively()
        outputConfiguration.mkdirs()
        outputAssets.mkdirs()

        configurationDir.copyRecursively(outputConfiguration, overwrite = true)
        resourcepackDir.resolve("assets").copyRecursively(outputAssets, overwrite = true)
        attributionFile.copyTo(outputRoot.resolve("ATTRIBUTION.md"), overwrite = true)
        outputRoot.resolve("pack.yml").writeText(
            """
            author: EllanServer
            version: $projectVersion
            description: MahjongPaper CraftEngine assets
            namespace: mahjongpaper
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val contentFiles =
            outputRoot
                .walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(outputRoot).invariantSeparatorsPath }
                .sorted()
                .toList()
        val manifestName = "_bundle_manifest.sha256"
        val manifest =
            contentFiles.joinToString("\n", postfix = "\n") { relativePath ->
                "${sha256(outputRoot.resolve(relativePath))}  $relativePath"
            }
        outputRoot.resolve(manifestName).writeText(manifest, Charsets.UTF_8)
        outputRoot.resolve("_bundle_index.txt").writeText(
            (contentFiles + manifestName).sorted().joinToString("\n", postfix = "\n"),
            Charsets.UTF_8,
        )
    }

    private fun verifyTileFactory(configurationFiles: List<File>, resourcepackDir: File) {
        val configured =
            configurationFiles
                .asSequence()
                .flatMap { file ->
                    Regex("(?m)^\\s*-\\s+tile:\\s+([a-z0-9_]+)\\s*$")
                        .findAll(file.readText(Charsets.UTF_8))
                        .map { it.groupValues[1] }
                }.toSet()
        val assets =
            resourcepackDir
                .resolve("assets/mahjongcraft/items/mahjong_tile")
                .listFiles { file -> file.isFile && file.extension == "json" }
                .orEmpty()
                .map { it.nameWithoutExtension }
                .toSet()
        require(configured == assets) {
            "CraftEngine tile factory differs from resource-pack items; " +
                "missing=${assets - configured}, extra=${configured - assets}"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
