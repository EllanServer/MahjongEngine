package top.ellan.mahjong.compat

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class CraftEngineBundleTest {
    @Test
    fun `generated craftengine items config is bundled`() {
        val stream = javaClass.classLoader.getResourceAsStream("craftengine/mahjongpaper/configuration/items/mahjong_tiles.yml")
        assertNotNull(stream)

        val text = stream.bufferedReader().use { it.readText() }
        assertContains(text, "items:")
        assertContains(text, "mahjongpaper:east:")
        assertContains(text, "item-model: mahjongcraft:mahjong_tile/east")
    }

    @Test
    fun `generated craftengine bundle index is bundled`() {
        val stream = javaClass.classLoader.getResourceAsStream("craftengine/mahjongpaper/_bundle_index.txt")
        assertNotNull(stream)

        val text = stream.bufferedReader().use { it.readText() }
        assertContains(text, "pack.yml")
        assertContains(text, "configuration/items/mahjong_tiles.yml")
        assertContains(text, "resourcepack/assets/mahjongcraft/items/mahjong_tile/east.json")
        assertContains(text, "_bundle_manifest.sha256")
    }

    @Test
    fun `generated craftengine bundle manifest authenticates every indexed content file`() {
        val index =
            javaClass.classLoader
                .getResourceAsStream("craftengine/mahjongpaper/_bundle_index.txt")
                ?.bufferedReader()
                ?.use { it.readLines().filter(String::isNotBlank).toSet() }
        val manifest =
            javaClass.classLoader
                .getResourceAsStream("craftengine/mahjongpaper/_bundle_manifest.sha256")
                ?.bufferedReader()
                ?.use { it.readLines().filter(String::isNotBlank) }
        assertNotNull(index)
        assertNotNull(manifest)

        val authenticated =
            manifest.associate { line ->
                val separator = line.indexOf("  ")
                assertTrue(separator == 64, "Malformed manifest line")
                line.substring(separator + 2) to line.substring(0, separator)
            }
        assertTrue(authenticated.keys == index - "_bundle_manifest.sha256")
        authenticated.forEach { (relativePath, expected) ->
            val bytes =
                javaClass.classLoader
                    .getResourceAsStream("craftengine/mahjongpaper/$relativePath")
                    ?.use { it.readAllBytes() }
            assertNotNull(bytes)
            val actual =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            assertTrue(actual == expected, "Hash mismatch for $relativePath")
        }
    }

    @Test
    fun `table visual embeds lowered centered 3x3 hitbox in craftengine bundle`() {
        val stream = javaClass.classLoader.getResourceAsStream("craftengine/mahjongpaper/configuration/items/mahjong_tiles.yml")
        assertNotNull(stream)

        val text = stream.bufferedReader().use { it.readText() }
        assertTrue(
            Regex(
                """mahjongpaper:table_visual:.*?hitboxes:\s+- position: -1,-1\.5,-1.*?position: -1,-1\.5,0.*?position: -1,-1\.5,1.*?position: 0,-1\.5,-1.*?position: 0,-1\.5,0.*?position: 0,-1\.5,1.*?position: 1,-1\.5,-1.*?position: 1,-1\.5,0.*?position: 1,-1\.5,1""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(text),
        )
    }

    @Test
    fun `fallback table hitbox furniture is lowered by half a block in craftengine bundle`() {
        val stream = javaClass.classLoader.getResourceAsStream("craftengine/mahjongpaper/configuration/items/mahjong_tiles.yml")
        assertNotNull(stream)

        val text = stream.bufferedReader().use { it.readText() }
        assertTrue(
            Regex(
                """mahjongpaper:table_hitbox:.*?hitboxes:\s+- position: -1,-1\.5,-1.*?position: -1,-1\.5,0.*?position: -1,-1\.5,1.*?position: 0,-1\.5,-1.*?position: 0,-1\.5,0.*?position: 0,-1\.5,1.*?position: 1,-1\.5,-1.*?position: 1,-1\.5,0.*?position: 1,-1\.5,1""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(text),
        )
    }

    @Test
    fun `seat furniture hitboxes are lowered by half a block in craftengine bundle`() {
        val stream = javaClass.classLoader.getResourceAsStream("craftengine/mahjongpaper/configuration/items/mahjong_tiles.yml")
        assertNotNull(stream)

        val text = stream.bufferedReader().use { it.readText() }
        assertTrue(
            Regex(
                """mahjongpaper:seat_chair:.*?hitboxes:\s+- type: shulker\s+position: 0,-1\.5,0""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(text),
        )
        assertTrue(
            Regex(
                """mahjongpaper:seat_hitbox:.*?hitboxes:\s+- type: shulker\s+position: 0,-1\.5,0""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(text),
        )
    }

    @Test
    fun `hand tile hitbox width stays narrower than hand tile spacing`() {
        val stream = javaClass.classLoader.getResourceAsStream("craftengine/mahjongpaper/configuration/items/mahjong_tiles.yml")
        assertNotNull(stream)

        val text = stream.bufferedReader().use { it.readText() }
        val match =
            Regex(
                """mahjongpaper:hand_tile_hitbox:.*?hitboxes:\s+- type: interaction\s+position: 0,0,0\s+width: ([0-9.]+)""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(text) ?: fail("Could not find hand tile hitbox width in generated CraftEngine bundle")

        val width = match.groupValues[1].toDouble()
        val handTileStep = 0.1125 + 0.0025
        assertTrue(width < handTileStep, "Expected hand tile hitbox width $width to stay below hand tile step $handTileStep")
    }
}
