package top.ellan.mahjong.build

import java.io.File

object CraftEngineBundleGenerator {
    private fun formatTileLabel(name: String): String =
        name.split('_').joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    fun writeCraftEngineBundle(
        enumSource: File,
        resourcepackDir: File,
        attributionFile: File,
        outputDir: File,
        projectVersion: String,
    ) {
        val outputRoot = outputDir.resolve("craftengine").resolve("mahjongpaper")
        val outputResourcepackDir = outputRoot.resolve("resourcepack")
        val outputAssetsDir = outputResourcepackDir.resolve("assets")
        val outputConfigDir = outputRoot.resolve("configuration").resolve("items")
        val tileNames =
            MahjongCodegen
                .parseMahjongTileNames(enumSource)
                .toMutableSet()
                .apply {
                    add("back")
                }.sorted()

        outputRoot.deleteRecursively()
        outputAssetsDir.mkdirs()
        outputConfigDir.mkdirs()

        resourcepackDir.resolve("assets").copyRecursively(outputAssetsDir, overwrite = true)
        attributionFile.copyTo(outputRoot.resolve("ATTRIBUTION.md"), overwrite = true)

        outputRoot.resolve("pack.yml").writeText(
            """
            author: openai and ellan
            version: $projectVersion
            description: MahjongPaper CraftEngine assets
            namespace: mahjongpaper
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val itemConfig =
            buildString {
                appendLine("items:")
                appendLine("  mahjongpaper:table_visual_model:")
                appendLine("    material: paper")
                appendLine("    settings:")
                appendLine("      tags:")
                appendLine("        - mahjongpaper:table_visual")
                appendLine("    data:")
                appendLine("      item-name: <!i><gray>Mahjong Table Visual</gray>")
                appendLine("    item-model: mahjongcraft:table_visual")
                appendLine("  mahjongpaper:table_visual:")
                appendLine("    material: paper")
                appendLine("    settings:")
                appendLine("      tags:")
                appendLine("        - mahjongpaper:table_visual")
                appendLine("    data:")
                appendLine("      item-name: <!i><gray>Mahjong Table Visual Furniture</gray>")
                appendLine("    item-model: mahjongcraft:table_visual")
                appendLine("    behavior:")
                appendLine("      type: furniture_item")
                appendLine("      rules:")
                appendLine("        ground:")
                appendLine("          rotation: four")
                appendLine("          alignment: center")
                appendLine("      furniture:")
                appendLine("        settings:")
                appendLine("          item: mahjongpaper:table_visual")

                appendLine("          sounds:")
                appendLine("            break: minecraft:block.wood.break")
                appendLine("            place: minecraft:block.wood.place")
                appendLine("            hit: minecraft:block.wood.hit")
                appendLine("        variants:")
                appendLine("          ground:")
                appendLine("            elements:")
                appendLine("              - item: mahjongpaper:table_visual_model")
                appendLine("                display-transform: none")
                appendLine("                billboard: fixed")
                appendLine("                position: 0,0,0")
                appendLine("                translation: 0,0,0")
                appendLine("                shadow-radius: 0")
                appendLine("                shadow-strength: 0")
                appendLine("            hitboxes:")
                listOf(
                    "-1,-1.5,-1",
                    "-1,-1.5,0",
                    "-1,-1.5,1",
                    "0,-1.5,-1",
                    "0,-1.5,0",
                    "0,-1.5,1",
                    "1,-1.5,-1",
                    "1,-1.5,0",
                    "1,-1.5,1",
                ).forEach { position ->
                    appendLine("              - position: $position")
                    appendLine("                type: shulker")
                    appendLine("                direction: up")
                    appendLine("                scale: 1")
                    appendLine("                peek: 0")
                    appendLine("                blocks-building: true")
                    appendLine("                interactive: false")
                    appendLine("                interaction-entity: false")
                }
                listOf(
                    Triple("p100", "mahjongcraft:stick_p100", "<!i><red>100 Point Stick</red>"),
                    Triple("p1000", "mahjongcraft:stick_p1000", "<!i><white>1000 Point Stick</white>"),
                    Triple("p5000", "mahjongcraft:stick_p5000", "<!i><gold>5000 Point Stick</gold>"),
                    Triple("p10000", "mahjongcraft:stick_p10000", "<!i><green>10000 Point Stick</green>"),
                ).forEach { (suffix, model, displayName) ->
                    appendLine("  mahjongpaper:$suffix:")
                    appendLine("    material: paper")
                    appendLine("    settings:")
                    appendLine("      tags:")
                    appendLine("        - mahjongpaper:stick_visual")
                    appendLine("    data:")
                    appendLine("      item-name: $displayName")
                    appendLine("    item-model: $model")
                    listOf(
                        "stick_x_$suffix" to "0,0,0",
                        "stick_z_$suffix" to "0,90,0",
                    ).forEach { (furnitureId, rotation) ->
                        appendLine("  mahjongpaper:$furnitureId:")
                        appendLine("    material: paper")
                        appendLine("    settings:")
                        appendLine("      tags:")
                        appendLine("        - mahjongpaper:stick_furniture")
                        appendLine("    data:")
                        appendLine("      item-name: $displayName")
                        appendLine("    item-model: $model")
                        appendLine("    behavior:")
                        appendLine("      type: furniture_item")
                        appendLine("      rules:")
                        appendLine("        ground:")
                        appendLine("          rotation: four")
                        appendLine("          alignment: center")
                        appendLine("      furniture:")
                        appendLine("        settings:")
                        appendLine("          item: mahjongpaper:$furnitureId")

                        appendLine("        variants:")
                        appendLine("          ground:")
                        appendLine("            elements:")
                        appendLine("              - item: mahjongpaper:$suffix")
                        appendLine("                display-transform: none")
                        appendLine("                billboard: fixed")
                        appendLine("                position: 0,0,0")
                        appendLine("                translation: 0,0,0")
                        appendLine("                rotation: $rotation")
                        appendLine("                shadow-radius: 0")
                        appendLine("                shadow-strength: 0")
                    }
                }
                appendLine("  mahjongpaper:table_hitbox:")
                appendLine("    material: paper")
                appendLine("    settings:")
                appendLine("      tags:")
                appendLine("        - mahjongpaper:table_hitbox")
                appendLine("    data:")
                appendLine("      item-name: <!i><gray>Mahjong Table Hitbox</gray>")
                appendLine("    item-model: mahjongcraft:mahjong_tile/back")
                appendLine("    behavior:")
                appendLine("      type: furniture_item")
                appendLine("      rules:")
                appendLine("        ground:")
                appendLine("          rotation: four")
                appendLine("          alignment: center")
                appendLine("      furniture:")
                appendLine("        settings:")
                appendLine("          item: mahjongpaper:table_hitbox")

                appendLine("          sounds:")
                appendLine("            break: minecraft:block.wood.break")
                appendLine("            place: minecraft:block.wood.place")
                appendLine("            hit: minecraft:block.wood.hit")
                appendLine("        variants:")
                appendLine("          ground:")
                appendLine("            elements:")
                appendLine("              - item: mahjongpaper:back")
                appendLine("                display-transform: none")
                appendLine("                billboard: fixed")
                appendLine("                position: 0,0,0")
                appendLine("                translation: 0,-16,0")
                appendLine("                shadow-radius: 0")
                appendLine("                shadow-strength: 0")
                appendLine("            hitboxes:")
                listOf(
                    "-1,-1.5,-1",
                    "-1,-1.5,0",
                    "-1,-1.5,1",
                    "0,-1.5,-1",
                    "0,-1.5,0",
                    "0,-1.5,1",
                    "1,-1.5,-1",
                    "1,-1.5,0",
                    "1,-1.5,1",
                ).forEach { position ->
                    appendLine("              - position: $position")
                    appendLine("                type: shulker")
                    appendLine("                direction: up")
                    appendLine("                scale: 1")
                    appendLine("                peek: 0")
                    appendLine("                blocks-building: true")
                    appendLine("                interactive: false")
                    appendLine("                interaction-entity: false")
                }
                appendLine("  mahjongpaper:hand_tile_hitbox:")
                appendLine("    material: paper")
                appendLine("    settings:")
                appendLine("      tags:")
                appendLine("        - mahjongpaper:hand_tile_hitbox")
                appendLine("    data:")
                appendLine("      item-name: <!i><gray>Mahjong Hand Tile Hitbox</gray>")
                appendLine("    item-model: mahjongcraft:mahjong_tile/back")
                appendLine("    behavior:")
                appendLine("      type: furniture_item")
                appendLine("      rules:")
                appendLine("        ground:")
                appendLine("          rotation: four")
                appendLine("          alignment: center")
                appendLine("      furniture:")
                appendLine("        settings:")
                appendLine("          item: mahjongpaper:hand_tile_hitbox")

                appendLine("        variants:")
                appendLine("          ground:")
                appendLine("            elements:")
                appendLine("              - item: mahjongpaper:back")
                appendLine("                display-transform: none")
                appendLine("                billboard: fixed")
                appendLine("                position: 0,0,0")
                appendLine("                translation: 0,-16,0")
                appendLine("                shadow-radius: 0")
                appendLine("                shadow-strength: 0")
                appendLine("            hitboxes:")
                appendLine("              - type: interaction")
                appendLine("                position: 0,0,0")
                appendLine("                width: 0.1")
                appendLine("                height: 0.18")
                appendLine("                blocks-building: false")
                appendLine("                interactive: true")
                appendLine("                invisible: true")
                appendLine("  mahjongpaper:seat_chair_model:")
                appendLine("    material: paper")
                appendLine("    settings:")
                appendLine("      tags:")
                appendLine("        - mahjongpaper:seat_visual")
                appendLine("    data:")
                appendLine("      item-name: <!i><gray>Mahjong Seat Chair</gray>")
                appendLine("    item-model: mahjongcraft:seat_chair")
                appendLine("  mahjongpaper:seat_chair:")
                appendLine("    material: paper")
                appendLine("    settings:")
                appendLine("      tags:")
                appendLine("        - mahjongpaper:seat_visual")
                appendLine("    data:")
                appendLine("      item-name: <!i><gray>Mahjong Seat Chair Furniture</gray>")
                appendLine("    item-model: mahjongcraft:seat_chair")
                appendLine("    behavior:")
                appendLine("      type: furniture_item")
                appendLine("      rules:")
                appendLine("        ground:")
                appendLine("          rotation: four")
                appendLine("          alignment: center")
                appendLine("      furniture:")
                appendLine("        settings:")
                appendLine("          item: mahjongpaper:seat_chair")

                appendLine("        variants:")
                appendLine("          ground:")
                appendLine("            elements:")
                appendLine("              - item: mahjongpaper:seat_chair_model")
                appendLine("                display-transform: none")
                appendLine("                billboard: fixed")
                appendLine("                position: 0,0,0")
                appendLine("                translation: 0,0,0")
                appendLine("                shadow-radius: 0")
                appendLine("                shadow-strength: 0")
                appendLine("            hitboxes:")
                appendLine("              - type: shulker")
                appendLine("                position: 0,-1.5,0")
                appendLine("                blocks-building: false")
                appendLine("                interactive: true")
                appendLine("                invisible: true")
                appendLine("                seats:")
                appendLine("                  - 0,-1.5,0")
                appendLine("  mahjongpaper:seat_hitbox:")
                appendLine("    material: paper")
                appendLine("    settings:")
                appendLine("      tags:")
                appendLine("        - mahjongpaper:seat_hitbox")
                appendLine("    data:")
                appendLine("      item-name: <!i><gray>Mahjong Seat Hitbox</gray>")
                appendLine("    item-model: mahjongcraft:mahjong_tile/back")
                appendLine("    behavior:")
                appendLine("      type: furniture_item")
                appendLine("      rules:")
                appendLine("        ground:")
                appendLine("          rotation: four")
                appendLine("          alignment: center")
                appendLine("      furniture:")
                appendLine("        settings:")
                appendLine("          item: mahjongpaper:seat_hitbox")

                appendLine("        variants:")
                appendLine("          ground:")
                appendLine("            elements:")
                appendLine("              - item: mahjongpaper:back")
                appendLine("                display-transform: none")
                appendLine("                billboard: fixed")
                appendLine("                position: 0,0,0")
                appendLine("                translation: 0,-16,0")
                appendLine("                shadow-radius: 0")
                appendLine("                shadow-strength: 0")
                appendLine("            hitboxes:")
                appendLine("              - type: shulker")
                appendLine("                position: 0,-1.5,0")
                appendLine("                blocks-building: false")
                appendLine("                interactive: true")
                appendLine("                invisible: true")
                appendLine("                seats:")
                appendLine("                  - 0,-1.5,0")
                tileNames.forEach { tileName ->
                    appendLine("  mahjongpaper:$tileName:")
                    appendLine("    material: paper")
                    appendLine("    settings:")
                    appendLine("      tags:")
                    appendLine("        - mahjongpaper:mahjong_tile")
                    appendLine("    data:")
                    appendLine("      item-name: <!i><white>${formatTileLabel(tileName)}</white>")
                    appendLine("    item-model: mahjongcraft:mahjong_tile/$tileName")
                }
                listOf(
                    Triple("tile_standing", "0,0,0", false),
                    Triple("tile_standing_face_down", "0,180,0", true),
                    Triple("tile_flat_face_up", "-90,0,0", false),
                    Triple("tile_flat_face_down", "90,0,0", true),
                ).forEach { (prefix, rotation, faceDown) ->
                    tileNames.forEach { tileName ->
                        appendLine("  mahjongpaper:${prefix}_$tileName:")
                        appendLine("    material: paper")
                        appendLine("    settings:")
                        appendLine("      tags:")
                        appendLine("        - mahjongpaper:mahjong_tile_furniture")
                        appendLine("    data:")
                        appendLine("      item-name: <!i><white>${formatTileLabel(tileName)} ${prefix.replace('_', ' ')}</white>")
                        appendLine("    item-model: mahjongcraft:mahjong_tile/${if (faceDown) "back" else tileName}")
                        appendLine("    behavior:")
                        appendLine("      type: furniture_item")
                        appendLine("      rules:")
                        appendLine("        ground:")
                        appendLine("          rotation: four")
                        appendLine("          alignment: center")
                        appendLine("      furniture:")
                        appendLine("        settings:")
                        appendLine("          item: mahjongpaper:${prefix}_$tileName")

                        appendLine("        variants:")
                        appendLine("          ground:")
                        appendLine("            elements:")
                        appendLine("              - item: mahjongpaper:${if (faceDown) "back" else tileName}")
                        appendLine("                display-transform: head")
                        appendLine("                billboard: fixed")
                        appendLine("                position: 0,0,0")
                        appendLine("                translation: 0,0,0")
                        appendLine("                rotation: $rotation")
                        appendLine("                shadow-radius: 0")
                        appendLine("                shadow-strength: 0")
                    }
                }
            }
        outputConfigDir.resolve("mahjong_tiles.yml").writeText(itemConfig, Charsets.UTF_8)

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
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                outputRoot.resolve(relativePath).inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                }
                "${digest.digest().joinToString("") { "%02x".format(it) }}  $relativePath"
            }
        outputRoot.resolve(manifestName).writeText(manifest, Charsets.UTF_8)
        val bundleFiles = (contentFiles + manifestName).sorted()
        outputRoot.resolve("_bundle_index.txt").writeText(bundleFiles.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}
