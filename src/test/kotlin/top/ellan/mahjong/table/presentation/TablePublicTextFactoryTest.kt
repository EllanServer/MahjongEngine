package top.ellan.mahjong.table.presentation

import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import top.ellan.mahjong.i18n.MessageService
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.table.core.MahjongTableSession
import top.ellan.mahjong.table.core.TableRuntimeServices
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TablePublicTextFactoryTest {
    @Test
    fun `round label follows each ruleset instead of reusing riichi honba text`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = mock(MahjongTableSession::class.java)
        val messages = MessageService()
        val factory = TablePublicTextFactory(session)

        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.messages()).thenReturn(messages)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.roundWind()).thenReturn(SeatWind.SOUTH)
        `when`(session.roundIndex()).thenReturn(2)
        `when`(session.honbaCount()).thenReturn(3)

        `when`(session.currentVariant()).thenReturn(MahjongVariant.RIICHI)
        assertEquals("South 3 | Honba 3", factory.roundDisplay(Locale.ENGLISH))

        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
        assertEquals("South 3", factory.roundDisplay(Locale.ENGLISH))

        `when`(session.currentVariant()).thenReturn(MahjongVariant.SICHUAN)
        assertEquals("Hand 7", factory.roundDisplay(Locale.ENGLISH))
        assertEquals("第 7 副", factory.roundDisplay(Locale.SIMPLIFIED_CHINESE))
        assertEquals("第7副", factory.roundDisplay(Locale.JAPAN))
    }

    @Test
    fun `active center stays empty until a public action needs attention`() {
        val session = mock(MahjongTableSession::class.java)
        val factory = TablePublicTextFactory(session)

        `when`(session.isStarted()).thenReturn(true)
        `when`(session.publicLocale()).thenReturn(Locale.ENGLISH)
        `when`(session.publicLastActionSummary(Locale.ENGLISH)).thenReturn("")

        assertEquals("", factory.publicCenterText())

        `when`(session.publicLastActionSummary(Locale.ENGLISH)).thenReturn("EastPlayer Ron")
        assertEquals("EastPlayer Ron", factory.publicCenterText())
    }

    @Test
    fun `seat display name follows dealer rotation`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = mock(MahjongTableSession::class.java)
        val messages = MessageService()
        val factory = TablePublicTextFactory(session)

        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.messages()).thenReturn(messages)
        `when`(session.hasRoundController()).thenReturn(true)
        `when`(session.dealerSeat()).thenReturn(SeatWind.SOUTH)

        assertEquals("North", factory.seatDisplayName(SeatWind.EAST, Locale.ENGLISH))
        assertEquals("East", factory.seatDisplayName(SeatWind.SOUTH, Locale.ENGLISH))
        assertEquals("South", factory.seatDisplayName(SeatWind.WEST, Locale.ENGLISH))
        assertEquals("West", factory.seatDisplayName(SeatWind.NORTH, Locale.ENGLISH))
    }

    @Test
    fun `seat display name stays unchanged before a round controller exists`() {
        val plugin = mock(TableRuntimeServices::class.java)
        val session = mock(MahjongTableSession::class.java)
        val messages = MessageService()
        val factory = TablePublicTextFactory(session)

        `when`(session.plugin()).thenReturn(plugin)
        `when`(plugin.messages()).thenReturn(messages)
        `when`(session.hasRoundController()).thenReturn(false)

        assertEquals("East", factory.seatDisplayName(SeatWind.EAST, Locale.ENGLISH))
        assertEquals("South", factory.seatDisplayName(SeatWind.SOUTH, Locale.ENGLISH))
        assertEquals("West", factory.seatDisplayName(SeatWind.WEST, Locale.ENGLISH))
        assertEquals("North", factory.seatDisplayName(SeatWind.NORTH, Locale.ENGLISH))
    }
}
