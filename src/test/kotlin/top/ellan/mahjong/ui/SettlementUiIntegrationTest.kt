package top.ellan.mahjong.ui

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.compat.CraftEngineService
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.riichi.RoundResolution
import top.ellan.mahjong.riichi.model.DoubleYakuman
import top.ellan.mahjong.riichi.model.ExhaustiveDraw
import top.ellan.mahjong.riichi.model.MahjongTile
import top.ellan.mahjong.riichi.model.ScoreItem
import top.ellan.mahjong.riichi.model.ScoreSettlement
import top.ellan.mahjong.riichi.model.SettlementPayment
import top.ellan.mahjong.riichi.model.SettlementPaymentType
import top.ellan.mahjong.riichi.model.YakuSettlement
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableRuntimeServices
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettlementUiIntegrationTest {
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `summary lore includes round dealer draw type and settled players`() {
        val session = mockSession()
        val resolution =
            RoundResolution(
                title = "Ron",
                yakuSettlements =
                    listOf(
                        YakuSettlement(
                            displayName = "Alice",
                            uuid = UUID.randomUUID().toString(),
                            yakuList = listOf("reach"),
                            yakumanList = emptyList(),
                            doubleYakumanList = emptyList(),
                            riichi = true,
                            winningTile = MahjongTile.M1,
                            hands = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3),
                            fuuroList = emptyList(),
                            doraIndicators = emptyList(),
                            uraDoraIndicators = emptyList(),
                            fu = 30,
                            han = 1,
                            score = 1000,
                        ),
                    ),
                scoreSettlement =
                    ScoreSettlement(
                        "Ron",
                        listOf(
                            ScoreItem("Alice", UUID.randomUUID().toString(), 25000, 12000),
                            ScoreItem("Bob", UUID.randomUUID().toString(), 25000, -12000),
                        ),
                    ),
                draw = ExhaustiveDraw.NORMAL,
            )

        val rendered = SettlementUi.summaryLore(session, resolution, Locale.ENGLISH).map(plain::serialize)

        assertTrue(rendered.any { it.contains("East 2") })
        assertTrue(rendered.any { it.contains("Alice") })
        assertTrue(rendered.any { it.contains("Normal draw") || it.contains("NORMAL") })
        assertTrue(rendered.any { it.contains("2") })
    }

    @Test
    fun `settlement lore includes score hand meld yaku and dora details`() {
        val session = mockSession()
        val ronPayer = UUID.randomUUID().toString()
        val paoPayer = UUID.randomUUID().toString()
        val settlement =
            YakuSettlement(
                displayName = "Alice",
                uuid = UUID.randomUUID().toString(),
                yakuList = listOf("reach"),
                yakumanList = listOf("kokushimuso"),
                doubleYakumanList = listOf(DoubleYakuman.SUANKO_TANKI),
                riichi = true,
                winningTile = MahjongTile.M1,
                hands = listOf(MahjongTile.M1, MahjongTile.M2, MahjongTile.M3),
                fuuroList = listOf(true to listOf(MahjongTile.P1, MahjongTile.P2, MahjongTile.P3)),
                doraIndicators = listOf(MahjongTile.S1),
                uraDoraIndicators = listOf(MahjongTile.S2),
                fu = 40,
                han = 3,
                score = 7700,
                paymentBreakdown =
                    listOf(
                        SettlementPayment(ronPayer, 3900, SettlementPaymentType.RON),
                        SettlementPayment(paoPayer, 3900, SettlementPaymentType.PAO, "DAISANGEN"),
                        SettlementPayment("", 1000, SettlementPaymentType.RIICHI_POOL),
                    ),
                redFiveCount = 1,
                nagashiMangan = true,
            )

        val rendered = SettlementUi.settlementLore(Locale.ENGLISH, session, settlement).map(plain::serialize)

        assertTrue(rendered.any { it.contains("40") && it.contains("3") })
        assertTrue(rendered.any { it.contains("7,700") || it.contains("7700") })
        assertTrue(rendered.any { it.contains("Riichi") || it.contains("Yes") })
        assertTrue(rendered.any { it.contains("1m") || it.contains("M1") || it.contains("m1") })
        assertTrue(rendered.any { it.contains("1p") || it.contains("P1") || it.contains("p1") })
        assertTrue(rendered.any { it.contains("Riichi") || it.contains("reach", ignoreCase = true) })
        assertTrue(rendered.any { it.contains("3,900") || it.contains("3900") })
    }

    @Test
    fun `mahjong soul game score is shown only for riichi settlements`() {
        val session = mock(MahjongTableSession::class.java)

        `when`(session.currentVariant()).thenReturn(MahjongVariant.RIICHI)
        assertTrue(SettlementUi.shouldShowMahjongSoulGameScore(session))

        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
        assertFalse(SettlementUi.shouldShowMahjongSoulGameScore(session))

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        assertFalse(SettlementUi.shouldShowMahjongSoulGameScore(session))
    }

    private fun mockSession(): MahjongTableSession {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = mock(MahjongTableSession::class.java)
        val craftEngine = mock(CraftEngineService::class.java)
        val messages = MessageService()

        `when`(plugin.messages()).thenReturn(messages)
        `when`(plugin.craftEngine()).thenReturn(craftEngine)
        `when`(session.plugin()).thenReturn(plugin)
        `when`(session.currentVariant()).thenReturn(MahjongVariant.RIICHI)
        `when`(session.roundDisplay(Locale.ENGLISH)).thenReturn("East 2")
        `when`(session.dealerName(Locale.ENGLISH)).thenReturn("Alice")
        `when`(session.finalStandings()).thenReturn(emptyList())
        return session
    }
}
