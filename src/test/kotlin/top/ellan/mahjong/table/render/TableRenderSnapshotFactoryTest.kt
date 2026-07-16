package top.ellan.mahjong.table.render

import org.bukkit.Location
import org.bukkit.entity.Player
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.render.scene.MeldView
import top.ellan.mahjong.table.core.MahjongTableSession
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableRenderSnapshotFactoryTest {
    @Test
    fun `render snapshot preserves serialized viewer ordering and membership`() {
        val session = mock(MahjongTableSession::class.java)
        val factory = TableRenderSnapshotFactory()

        val eastId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val southId = UUID.fromString("80000000-0000-0000-0000-000000000012")
        val westId = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")
        val northId = UUID.fromString("00000000-0000-0000-0000-000000000014")
        val eastViewer = mock(Player::class.java)
        val duplicateEastViewer = mock(Player::class.java)
        val southViewer = mock(Player::class.java)
        val westViewer = mock(Player::class.java)

        assertTrue(southId.compareTo(eastId) < 0)
        assertTrue(southId.toString().compareTo(eastId.toString()) > 0)
        `when`(eastViewer.uniqueId).thenReturn(eastId)
        `when`(duplicateEastViewer.uniqueId).thenReturn(eastId)
        `when`(southViewer.uniqueId).thenReturn(southId)
        `when`(westViewer.uniqueId).thenReturn(westId)
        `when`(session.center()).thenReturn(Location(null, 0.0, 64.0, 0.0))
        `when`(session.viewers()).thenReturn(
            listOf(southViewer, westViewer, duplicateEastViewer, eastViewer),
        )
        `when`(session.isStarted()).thenReturn(false)
        `when`(session.currentVariant()).thenReturn(MahjongVariant.GB)
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
        `when`(session.playerAt(SeatWind.NORTH)).thenReturn(northId)

        doAnswer { invocation ->
            (invocation.arguments[0] as UUID?)?.toString() ?: ""
        }.`when`(session).displayName(ArgumentMatchers.nullable(UUID::class.java))

        for (wind in SeatWind.values()) {
            `when`(session.publicSeatStatus(wind)).thenReturn("")
            `when`(session.stickLayoutCount(wind)).thenReturn(0)
            `when`(session.cornerSticks(wind)).thenReturn(emptyList())
        }
        for (playerId in listOf(eastId, southId, westId, northId)) {
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
        val northSeat = snapshot.seat(SeatWind.NORTH)

        assertTrue(eastSeat.online())
        assertTrue(southSeat.online())
        assertTrue(westSeat.online())
        assertTrue(!northSeat.online())
        assertEquals(listOf(westId, southId), eastSeat.viewerIdsExcluding())
        assertEquals(listOf(eastId, westId), southSeat.viewerIdsExcluding())
        assertEquals(listOf(eastId, southId), westSeat.viewerIdsExcluding())
        assertTrue(northSeat.viewerIdsExcluding().isEmpty())
        assertEquals(
            "7fffffff-ffff-ffff-ffff-ffffffffffff80000000-0000-0000-0000-000000000012",
            eastSeat.viewerMembershipSignature(),
        )
        assertEquals(
            "00000000-0000-0000-0000-0000000000117fffffff-ffff-ffff-ffff-ffffffffffff",
            southSeat.viewerMembershipSignature(),
        )
        assertEquals(
            "00000000-0000-0000-0000-00000000001180000000-0000-0000-0000-000000000012",
            westSeat.viewerMembershipSignature(),
        )
        assertTrue(northSeat.viewerMembershipSignature().isEmpty())
        assertEquals(MahjongVariant.GB, snapshot.variant())
        assertEquals(144, snapshot.wallCapacity())
        assertTrue(!snapshot.usesDeadWall())

        `when`(session.playerAt(SeatWind.NORTH)).thenReturn(null)
        val emptyNorthSeat = factory.create(session, 2L, 0L).seat(SeatWind.NORTH)
        assertEquals(listOf(eastId, westId, southId), emptyNorthSeat.viewerIdsExcluding())
        assertEquals(
            "00000000-0000-0000-0000-000000000011" +
                "7fffffff-ffff-ffff-ffff-ffffffffffff" +
                "80000000-0000-0000-0000-000000000012",
            emptyNorthSeat.viewerMembershipSignature(),
        )

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
        for (wind in SeatWind.values()) {
            val seat = snapshot.seat(wind)
            assertTrue(!seat.online())
            assertTrue(seat.viewerIdsExcluding().isEmpty())
            assertTrue(seat.viewerMembershipSignature().isEmpty())
        }
        verify(session, never()).doraIndicators()
    }
}
