package top.ellan.mahjong.i18n

import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageServiceTest {
    private val messages = LocalizedMessages()

    @Test
    fun `plain returns english text for english locale`() {
        assertEquals(
            bundleValue("language/messages.properties", "command.action.ron"),
            messages.plain(Locale.ENGLISH, "command.action.ron"),
        )
    }

    @Test
    fun `plain returns chinese text for zh CN locale`() {
        val chinese = messages.plain(Locale.forLanguageTag("zh-CN"), "command.action.ron")
        assertEquals(bundleValue("language/messages_zh_CN.properties", "command.action.ron"), chinese)
    }

    @Test
    fun `normalize locale matches supported language and region variants`() {
        assertEquals(Locale.ENGLISH, messages.normalizeLocale("en-US"))
        assertEquals(Locale.forLanguageTag("zh-TW"), messages.normalizeLocale("zh-Hant"))
        assertEquals(Locale.forLanguageTag("zh-TW"), messages.normalizeLocale("zh-TW"))
        assertEquals(Locale.forLanguageTag("zh-HK"), messages.normalizeLocale("zh-HK"))
        assertEquals(Locale.forLanguageTag("zh-MO"), messages.normalizeLocale("zh-MO"))
        assertEquals(Locale.forLanguageTag("zh-CN"), messages.normalizeLocale("zh-SG"))
        assertEquals(Locale.JAPAN, messages.normalizeLocale("ja"))
        assertEquals(Locale.JAPAN, messages.normalizeLocale("ja_JP"))
        assertEquals(Locale.JAPAN, messages.normalizeLocale("ja-JP"))
        assertEquals(Locale.forLanguageTag("zh-CN"), messages.normalizeLocale("de-DE"))
    }

    @Test
    fun `render keeps command help text available`() {
        val rendered = messages.plain(Locale.ENGLISH, "command.help.create")
        assertContains(rendered, "/mahjong create")
        assertContains(rendered, "Create a new table")
    }

    @Test
    fun `render keeps paged command help chrome available`() {
        val subtitle = messages.plain(Locale.ENGLISH, "command.help.subtitle")
        val status =
            messages.plain(
                Locale.ENGLISH,
                "command.help.page_status",
                messages.number(Locale.ENGLISH, "page", 2),
                messages.number(Locale.ENGLISH, "pages", 4),
                messages.number(Locale.ENGLISH, "count", 31),
            )

        assertContains(subtitle, "/mahjong help <page>")
        assertContains(status, "2")
        assertContains(status, "4")
        assertContains(status, "31")
    }

    @Test
    fun `yaku labels are localized`() {
        assertEquals(bundleValue("language/messages.properties", "yaku.reach"), messages.plain(Locale.ENGLISH, "yaku.reach"))
        assertEquals(
            bundleValue("language/messages_zh_CN.properties", "yaku.reach"),
            messages.plain(Locale.forLanguageTag("zh-CN"), "yaku.reach"),
        )
        assertEquals(
            bundleValue("language/messages_zh_CN.properties", "yakuman.kokushimuso"),
            messages.plain(Locale.forLanguageTag("zh-CN"), "yakuman.kokushimuso"),
        )
    }

    @Test
    fun `traditional region bundles are addressable`() {
        assertEquals(
            bundleValue("language/messages_zh_TW.properties", "command.action.ron"),
            messages.plain(Locale.forLanguageTag("zh-TW"), "command.action.ron"),
        )
        assertEquals(
            bundleValue("language/messages_zh_HK.properties", "command.action.ron"),
            messages.plain(Locale.forLanguageTag("zh-HK"), "command.action.ron"),
        )
        assertEquals(
            bundleValue("language/messages_zh_MO.properties", "command.action.ron"),
            messages.plain(Locale.forLanguageTag("zh-MO"), "command.action.ron"),
        )
    }

    @Test
    fun `Japanese bundle uses natural mahjong terminology`() {
        assertEquals("ロン", messages.plain(Locale.JAPAN, "command.action.ron"))
        assertEquals("ツモ", messages.plain(Locale.JAPAN, "table.action.tsumo"))
        assertEquals("チー", messages.plain(Locale.JAPAN, "table.action.chii"))
        assertEquals("ポン", messages.plain(Locale.JAPAN, "table.action.pon"))
        assertContains(messages.plain(Locale.JAPAN, "table.action.kan"), "カン")
        assertEquals("リーチ", messages.plain(Locale.JAPAN, "table.action.riichi"))
        assertEquals("花牌公開", messages.plain(Locale.JAPAN, "table.action.flower"))
        assertEquals("河を見る", messages.plain(Locale.JAPAN, "table.action.view_river"))
        assertEquals("席に戻る", messages.plain(Locale.JAPAN, "table.action.return_seat"))
        assertContains(messages.plain(Locale.JAPAN, "hud.round_compact"), "牌山")
    }

    @Test
    fun `render caches static components without placeholders`() {
        val first = messages.render(Locale.ENGLISH, "command.inspect_sent")
        val second = messages.render(Locale.ENGLISH, "command.inspect_sent")

        assertSame(first, second)
    }

    @Test
    fun `render keeps placeholder substitutions dynamic`() {
        val first =
            messages.plain(
                Locale.ENGLISH,
                "command.inspect_summary",
                messages.tag("table_id", "A"),
                messages.tag("center", "1"),
                messages.tag("anchor", "2"),
                messages.tag("span_x", "3"),
                messages.tag("span_z", "4"),
            )
        val second =
            messages.plain(
                Locale.ENGLISH,
                "command.inspect_summary",
                messages.tag("table_id", "B"),
                messages.tag("center", "5"),
                messages.tag("anchor", "6"),
                messages.tag("span_x", "7"),
                messages.tag("span_z", "8"),
            )

        assertContains(first, "A")
        assertContains(second, "B")
    }

    @Test
    fun `number formats integers with locale aware grouping`() {
        val english = messages.number(Locale.ENGLISH, "value", 25000)
        val chinese = messages.number(Locale.forLanguageTag("zh-CN"), "value", 25000)
        val japanese = messages.number(Locale.JAPAN, "value", 25000)

        val englishRendered = messages.plain(Locale.ENGLISH, "ui.score.total", english)
        val chineseRendered = messages.plain(Locale.forLanguageTag("zh-CN"), "ui.score.total", chinese)
        val japaneseRendered = messages.plain(Locale.JAPAN, "ui.score.total", japanese)

        assertContains(englishRendered, "25,000")
        assertContains(chineseRendered, "25,000")
        assertContains(japaneseRendered, "25,000")
    }

    @Test
    fun `message bundle index points to language resources`() {
        val stream = javaClass.classLoader.getResourceAsStream("i18n/_index.json")
        assertNotNull(stream)

        val text = stream.bufferedReader().use { it.readText() }
        assertContains(text, "language/messages.properties")
        assertContains(text, "language/messages_zh_CN.properties")
        assertContains(text, "language/messages_ja_JP.properties")
    }

    @Test
    fun `localized bundles contain every english message key`() {
        val english = loadBundle("language/messages.properties").stringPropertyNames()
        val localizedBundles =
            listOf(
                "language/messages_zh_CN.properties",
                "language/messages_zh_TW.properties",
                "language/messages_zh_HK.properties",
                "language/messages_zh_MO.properties",
                "language/messages_ja_JP.properties",
            )

        localizedBundles.forEach { resource ->
            val localized = loadBundle(resource).stringPropertyNames()
            val missing = english - localized
            assertTrue(missing.isEmpty(), "$resource is missing keys: $missing")
        }
    }

    @Test
    fun `message bundles do not contain duplicate keys`() {
        val resources =
            listOf(
                "language/messages.properties",
                "language/messages_zh_CN.properties",
                "language/messages_zh_TW.properties",
                "language/messages_zh_HK.properties",
                "language/messages_zh_MO.properties",
                "language/messages_ja_JP.properties",
            )

        resources.forEach { resource ->
            val stream = javaClass.classLoader.getResourceAsStream(resource)
            assertNotNull(stream, "Missing resource $resource")
            val keys =
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines
                        .map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') && '=' in it }
                        .map { it.substringBefore('=').trim() }
                        .toList()
                }
            val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(duplicates.isEmpty(), "$resource contains duplicate keys: $duplicates")
        }
    }

    @Test
    fun `Japanese bundle exactly matches english keys and placeholders`() {
        val english = loadBundle("language/messages.properties")
        val japanese = loadBundle("language/messages_ja_JP.properties")
        assertEquals(english.stringPropertyNames(), japanese.stringPropertyNames())

        english.stringPropertyNames().forEach { key ->
            val englishTemplate = english.getProperty(key)
            val japaneseTemplate = japanese.getProperty(key)
            assertEquals(
                angleTags(englishTemplate),
                angleTags(japaneseTemplate),
                "MiniMessage tags/placeholders differ for $key",
            )
            assertEquals(
                messageFormatArguments(englishTemplate),
                messageFormatArguments(japaneseTemplate),
                "MessageFormat arguments differ for $key",
            )
        }
    }

    @Test
    fun `every Japanese template is parseable MiniMessage`() {
        val japanese = loadBundle("language/messages_ja_JP.properties")
        val miniMessage = MiniMessage.miniMessage()

        japanese.stringPropertyNames().forEach { key ->
            assertNotNull(miniMessage.deserialize(japanese.getProperty(key)), "Could not parse $key")
        }
    }

    private fun bundleValue(
        resource: String,
        key: String,
    ): String {
        val bundle = loadBundle(resource)
        val value = bundle.getProperty(key)
        assertNotNull(value, "Missing key $key in $resource")
        return value
    }

    private fun loadBundle(resource: String): Properties {
        val stream = javaClass.classLoader.getResourceAsStream(resource)
        assertNotNull(stream, "Missing resource $resource")
        return Properties().apply {
            stream.reader(Charsets.UTF_8).use { load(it) }
        }
    }

    private fun angleTags(template: String): Set<String> = ANGLE_TAG.findAll(template).map { it.groupValues[1] }.toSet()

    private fun messageFormatArguments(template: String): Set<String> =
        MESSAGE_FORMAT_ARGUMENT.findAll(template).map { it.groupValues[1] }.toSet()

    private companion object {
        val ANGLE_TAG = Regex("(?<!/)<([a-z][a-z0-9_]*)>")
        val MESSAGE_FORMAT_ARGUMENT = Regex("\\{(\\d+)(?:,[^}]*)?}")
    }
}
