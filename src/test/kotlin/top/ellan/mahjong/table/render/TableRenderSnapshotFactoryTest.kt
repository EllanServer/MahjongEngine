package top.ellan.mahjong.table.render

import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.scene.MeldView
import top.ellan.mahjong.table.core.MahjongTableSession
import org.bukkit.Location
import org.bukkit.entity.Player
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableRenderSnapshotFactoryTest {
    @Test
    fun `render snapshot derives exclusions only for online seats`() {
        val session = mock(MahjongTableSession::class.java)
        val factory = TableRenderSnapshotFactory()

        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val southId = UUID.fromString("00000000-0000-0000-0000-000000000012")
        val westId = UUID.fromString("00000000-0000-0000-0000-000000000013")
        val eastViewer = mock(Player::class.java)
        val southViewer = mock(Player::class.java)

        `when`(eastViewer.uniqueId).thenReturn(eastId)
        `when`(southViewer.uniqueId).thenReturn(southId)
        `when`(session.center()).thenReturn(Location(null, 0.0, 64.0, 0.0))
        `when`(session.viewers()).thenReturn(listOf(eastViewer, southViewer))
        `when`(session.isStarted()).thenReturn(false)
        `when`(session.isRoundFinished()).thenReturn(false)
        `when`(session.remainingWallCount()).thenReturn(0)
        `when`(session.kanCount()).thenReturn(0)
        `when`(session.dicePoints()).thenReturn(0)
        `when`(session.roundIndex()).thenReturn(0)
        `when`(session.honbaCount()).thenReturn(0)
        `when`(session.dealerSeat()).thenReturn(SeatWind.EAST)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.openDoorSeat()).thenReturn(SeatWind.EAST)
        `when`(session.waitingDisplaySummary()).thenReturn("")
        `when`(session.ruleDisplaySummary()).thenReturn("")
        `when`(session.publicCenterText()).thenReturn("")
        `when`(session.lastPublicDiscardPlayerIdValue()).thenReturn(null)
        `when`(session.lastPublicDiscardTile()).thenReturn(null)
        `when`(session.doraIndicators()).thenReturn(emptyList())
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(eastId)
        `when`(session.playerAt(SeatWind.SOUTH)).thenReturn(southId)
        `when`(session.playerAt(SeatWind.WEST)).thenReturn(westId)
        `when`(session.playerAt(SeatWind.NORTH)).thenReturn(null)

        doAnswer { invocation ->
            (invocation.arguments[0] as UUID?)?.toString() ?: ""
        }.`when`(session).displayName(ArgumentMatchers.nullable(UUID::class.java))

        for (wind in SeatWind.values()) {
            `when`(session.publicSeatStatus(wind)).thenReturn("")
            `when`(session.stickLayoutCount(wind)).thenReturn(0)
            `when`(session.cornerSticks(wind)).thenReturn(emptyList())
        }
        for (playerId in listOf(eastId, southId, westId)) {
            `when`(session.points(playerId)).thenReturn(25000)
            `when`(session.isRiichi(playerId)).thenReturn(false)
            `when`(session.isReady(playerId)).thenReturn(false)
            `when`(session.isQueuedToLeave(playerId)).thenReturn(false)
            `when`(session.selectedHandTileIndex(playerId)).thenReturn(-1)
            `when`(session.riichiDiscardIndex(playerId)).thenReturn(-1)
            `when`(session.hand(playerId)).thenReturn(emptyList())
            `when`(session.discards(playerId)).thenReturn(emptyList())
            `when`(session.fuuro(playerId)).thenReturn(emptyList<MeldView>())
            `when`(session.scoringSticks(playerId)).thenReturn(emptyList())
        }

        val snapshot = factory.create(session, 1L, 0L)
        val eastSeat = snapshot.seat(SeatWind.EAST)
        val southSeat = snapshot.seat(SeatWind.SOUTH)
        val westSeat = snapshot.seat(SeatWind.WEST)

        assertTrue(eastSeat.online())
        assertTrue(southSeat.online())
        assertEquals(listOf(southId), eastSeat.viewerIdsExcluding())
        assertEquals(listOf(eastId), southSeat.viewerIdsExcluding())
        assertEquals(southId.toString(), eastSeat.viewerMembershipSignature())
        assertEquals(eastId.toString(), southSeat.viewerMembershipSignature())
        assertTrue(!westSeat.online())
        assertTrue(westSeat.viewerIdsExcluding().isEmpty())
        assertTrue(westSeat.viewerMembershipSignature().isEmpty())

        verify(session, never()).onlinePlayer(ArgumentMatchers.any(UUID::class.java))
        verify(session, never()).viewerIdsExcluding(ArgumentMatchers.any(UUID::class.java))
        verify(session, never()).viewerMembershipSignatureFor(ArgumentMatchers.any(UUID::class.java))
        verify(session, never()).doraIndicators()
    }

    @Test
    fun `waiting render snapshot does not request dora indicators`() {
        val session = mock(MahjongTableSession::class.java)
        val factory = TableRenderSnapshotFactory()

        `when`(session.center()).thenReturn(Location(null, 0.0, 64.0, 0.0))
        `when`(session.viewers()).thenReturn(emptyList())
        `when`(session.isStarted()).thenReturn(false)
        `when`(session.isRoundFinished()).thenReturn(false)
        `when`(session.remainingWallCount()).thenReturn(0)
        `when`(session.kanCount()).thenReturn(0)
        `when`(session.dicePoints()).thenReturn(0)
        `when`(session.roundIndex()).thenReturn(0)
        `when`(session.honbaCount()).thenReturn(0)
        `when`(session.dealerSeat()).thenReturn(SeatWind.EAST)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.openDoorSeat()).thenReturn(SeatWind.EAST)
        `when`(session.waitingDisplaySummary()).thenReturn("")
        `when`(session.ruleDisplaySummary()).thenReturn("")
        `when`(session.publicCenterText()).thenReturn("")
        `when`(session.lastPublicDiscardPlayerIdValue()).thenReturn(null)
        `when`(session.lastPublicDiscardTile()).thenReturn(null)
        for (wind in SeatWind.values()) {
            `when`(session.playerAt(wind)).thenReturn(null)
            `when`(session.publicSeatStatus(wind)).thenReturn("")
            `when`(session.stickLayoutCount(wind)).thenReturn(0)
            `when`(session.cornerSticks(wind)).thenReturn(emptyList())
        }

        val snapshot = factory.create(session, 1L, 0L)

        assertTrue(snapshot.doraIndicators().isEmpty())
        verify(session, never()).doraIndicators()
    }
}

