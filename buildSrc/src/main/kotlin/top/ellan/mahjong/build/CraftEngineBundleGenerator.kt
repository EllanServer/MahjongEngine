package top.ellan.mahjong.build

import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

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
        verifyOpeningDice(configurationFiles, resourcepackDir)
        verifyLocales(resourcepackDir)

        val outputRoot = outputDir.resolve("craftengine").resolve("mahjongpaper")
        val outputConfiguration = outputRoot.resolve("configuration")
        val outputAssets = outputRoot.resolve("resourcepack").resolve("assets")
        outputRoot.deleteRecursively()
        outputConfiguration.mkdirs()
        outputAssets.mkdirs()

        configurationDir.copyRecursively(outputConfiguration, overwrite = true)
        resourcepackDir.resolve("assets").copyRecursively(outputAssets, overwrite = true)
        writeClientDiceAnimation(resourcepackDir, outputAssets)
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

    private fun verifyOpeningDice(configurationFiles: List<File>, resourcepackDir: File) {
        val configuration =
            configurationFiles.joinToString("\n") { it.readText(Charsets.UTF_8) }
        val slots =
            Regex("\\{slot:\\s*([0-3]),")
                .findAll(configuration)
                .map { it.groupValues[1].toInt() }
                .toSet()
        require(slots == setOf(0, 1, 2, 3)) {
            "CraftEngine opening dice must declare exactly four fixed slots"
        }
        val expectedFaces = (1..6).toSet()
        listOf("single", "double").forEach { layout ->
            require(Regex("(?m)^\\s+${layout}_rolling:\\s*$").find(configuration) != null) {
                "CraftEngine opening dice $layout rolling variant is missing"
            }
            val faces =
                Regex("(?m)^\\s+${layout}_face_([1-6]):\\s*$")
                    .findAll(configuration)
                    .map { it.groupValues[1].toInt() }
                    .toSet()
            require(faces == expectedFaces) {
                "CraftEngine opening dice $layout variants are incomplete"
            }
        }
        require("mahjongpaper:opening_die_slot_\${slot}" in configuration) {
            "CraftEngine opening dice slot factory is missing"
        }
        require("mahjongpaper:dice_rolling_model" in configuration) {
            "CraftEngine client-side rolling dice model is missing"
        }
        require("item_model: mahjongcraft:dice/rolling" in configuration) {
            "CraftEngine rolling dice must use the client-animated resource-pack model"
        }
        require("dice_face_" !in configuration) {
            "Opening dice must use CE variants, not one furniture asset per face"
        }
        val modelFaces =
            resourcepackDir
                .resolve("assets/mahjongcraft/items/dice")
                .listFiles { file -> file.isFile && file.extension == "json" }
                .orEmpty()
                .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
                .toSet()
        require(modelFaces == expectedFaces) {
            "CraftEngine opening dice models must contain faces 1 through 6"
        }
        require(resourcepackDir.resolve("assets/mahjongcraft/items/dice/rolling.json").isFile) {
            "CraftEngine rolling dice item definition is missing"
        }
        require(resourcepackDir.resolve("assets/mahjongcraft/models/item/dice/dice_rolling.json").isFile) {
            "CraftEngine rolling dice model is missing"
        }
    }

    /**
     * Builds a vanilla animated texture from the six checked-in dice faces. One texture frame is
     * shown per client tick, matching the 20 Hz face cadence used by the 1.5 presentation without
     * requiring the server to select and transmit a model every tick.
     */
    private fun writeClientDiceAnimation(resourcepackDir: File, outputAssets: File) {
        val sourceDir = resourcepackDir.resolve("assets/mahjongcraft/textures/item/dice")
        val frames =
            (1..6).map { point ->
                val source = sourceDir.resolve("dice_$point.png")
                require(source.isFile) { "Opening dice texture is missing: ${source.name}" }
                requireNotNull(ImageIO.read(source)) {
                    "Opening dice texture is not a readable image: ${source.name}"
                }
            }
        val width = frames.first().width
        val height = frames.first().height
        require(width == height && frames.all { it.width == width && it.height == height }) {
            "Opening dice textures must be equally sized square frames"
        }

        val animation = BufferedImage(width, height * frames.size, BufferedImage.TYPE_INT_ARGB)
        val graphics = animation.createGraphics()
        try {
            frames.forEachIndexed { index, frame ->
                graphics.drawImage(frame, 0, index * height, null)
            }
        } finally {
            graphics.dispose()
        }

        val targetDir = outputAssets.resolve("mahjongcraft/textures/item/dice")
        targetDir.mkdirs()
        val texture = targetDir.resolve("dice_rolling.png")
        require(ImageIO.write(animation, "png", texture)) {
            "No PNG writer is available for the rolling dice texture"
        }
        targetDir.resolve("dice_rolling.png.mcmeta").writeText(
            """{"animation":{"frametime":1,"interpolate":false}}""" + "\n",
            Charsets.UTF_8,
        )
    }

    private fun verifyLocales(resourcepackDir: File) {
        val localeDir = resourcepackDir.resolve("assets/mahjongcraft/lang")
        val expected = setOf("en_us", "zh_cn", "zh_tw", "ja_jp")
        val files =
            localeDir
                .listFiles { file -> file.isFile && file.extension == "json" }
                .orEmpty()
                .associateBy { it.nameWithoutExtension }
        require(files.keys == expected) {
            "Mahjong locales must be exactly $expected; found=${files.keys}"
        }
        val keyPattern = Regex("\"([a-z0-9_.-]+)\"\\s*:")
        val placeholderPattern = Regex("%([0-9]+\\$)?s")
        val entries =
            files.mapValues { (_, file) ->
                val text = file.readText(Charsets.UTF_8)
                val keys = keyPattern.findAll(text).map { it.groupValues[1] }.toList()
                require(keys.size == keys.toSet().size) {
                    "Locale ${file.name} contains duplicate translation keys"
                }
                keys.associateWith { key ->
                    val value =
                        Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                            ?: error("Locale ${file.name} has an unsupported value for $key")
                    placeholderPattern.findAll(value).count()
                }
            }
        val canonical = entries.getValue("en_us")
        entries.forEach { (locale, localized) ->
            require(localized.keys == canonical.keys) {
                "Locale $locale key set differs from en_us; " +
                    "missing=${canonical.keys - localized.keys}, extra=${localized.keys - canonical.keys}"
            }
            require(localized == canonical) {
                val mismatched = canonical.keys.filter { canonical[it] != localized[it] }
                "Locale $locale placeholder counts differ for $mismatched"
            }
        }
        setOf(
            "mahjongpaper.command.help",
            "mahjongpaper.command.failed",
            "mahjongpaper.action.ready",
            "mahjongpaper.action.unready",
            "mahjongpaper.action.leave",
            "mahjongpaper.action.start",
            "mahjongpaper.action.transfer_owner_east",
            "mahjongpaper.action.transfer_owner_south",
            "mahjongpaper.action.transfer_owner_west",
            "mahjongpaper.action.transfer_owner_north",
        ).forEach { required ->
            require(required in canonical) { "Required client translation is missing: $required" }
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
