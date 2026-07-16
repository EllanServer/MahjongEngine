package top.ellan.mahjong.table.core

import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import top.ellan.mahjong.model.MahjongTile
import top.ellan.mahjong.model.MahjongVariant
import top.ellan.mahjong.model.SeatWind
import top.ellan.mahjong.riichi.ReactionOptions
import top.ellan.mahjong.riichi.ReactionResponses
import top.ellan.mahjong.riichi.model.MahjongRule
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionActionDeadlineCoordinatorTest {
    private val east = UUID.fromString("00000000-0000-0000-0000-00000000a001")
    private val south = UUID.fromString("00000000-0000-0000-0000-00000000a002")

    @Test
    fun `riichi turn discards last selectable tile exactly at deadline without sleeping`() {
        val clock = AtomicLong(1_000L)
        val session = turnSession(MahjongVariant.RIICHI, east)
        `when`(session.canSelectHandTile(east, 13)).thenReturn(true)
        `when`(session.discard(east, 13)).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.set(60_999L)
        coordinator.tick()
        verify(session, never()).discard(east, 13)

        clock.set(61_000L)
        coordinator.tick()

        verify(session).discard(east, 13)
    }

    @Test
    fun `visible countdown is actor scoped and rounds partial seconds up`() {
        val clock = AtomicLong(1_000L)
        val session = turnSession(MahjongVariant.RIICHI, east)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        assertEquals(60L, coordinator.secondsRemaining(east))
        assertEquals(0L, coordinator.secondsRemaining(south))

        clock.set(2_001L)
        assertEquals(59L, coordinator.secondsRemaining(east))

        clock.set(60_999L)
        assertEquals(1L, coordinator.secondsRemaining(east))

        clock.set(61_000L)
        assertEquals(0L, coordinator.secondsRemaining(east))
    }

    @Test
    fun `automatic discards shorten following turns until a manual discard restores sixty seconds`() {
        val clock = AtomicLong(1_000L)
        val remainingWall = AtomicInteger(50)
        val session = turnSession(MahjongVariant.RIICHI, east)
        `when`(session.remainingWallCount()).thenAnswer { remainingWall.get() }
        `when`(session.canSelectHandTile(east, 13)).thenReturn(true)
        lateinit var coordinator: SessionActionDeadlineCoordinator
        `when`(session.discard(east, 13)).thenAnswer {
            remainingWall.decrementAndGet()
            coordinator.recordDiscard(east)
            true
        }
        coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        assertEquals(60L, coordinator.secondsRemaining(east))

        clock.addAndGet(60_000L)
        coordinator.tick()
        assertEquals(30L, coordinator.secondsRemaining(east))

        clock.addAndGet(30_000L)
        coordinator.tick()
        assertEquals(15L, coordinator.secondsRemaining(east))

        clock.addAndGet(15_000L)
        coordinator.tick()
        assertEquals(10L, coordinator.secondsRemaining(east))

        clock.addAndGet(10_000L)
        coordinator.tick()
        assertEquals(10L, coordinator.secondsRemaining(east))

        remainingWall.decrementAndGet()
        coordinator.recordDiscard(east)
        assertEquals(60L, coordinator.secondsRemaining(east))
    }

    @Test
    fun `same player spends shared extra across reaction windows`() {
        val clock = AtomicLong(1_000L)
        val remainingWall = AtomicInteger(50)
        val session = baseSession(MahjongVariant.RIICHI)
        val options = ReactionOptions(false, true, false, emptyList())
        `when`(session.remainingWallCount()).thenAnswer { remainingWall.get() }
        `when`(session.players()).thenReturn(listOf(south))
        `when`(session.hasPendingReaction()).thenReturn(true)
        `when`(session.pendingReactionTileKey()).thenReturn("M5")
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.isReactionPending(south)).thenReturn(true)
        `when`(session.availableReactions(south)).thenReturn(options)
        `when`(session.react(south, ReactionResponses.SKIP)).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()

        // The first action completes after 5 seconds: 3 seconds base + 2 seconds from the
        // hand-shared 5-second extra pool. The state changes before the session records it.
        clock.set(6_000L)
        remainingWall.set(49)
        coordinator.recordAction(south)

        // Only 3 seconds of extra remain, so the second deadline is 3 base + 3 extra = 6 seconds.
        clock.set(11_999L)
        coordinator.tick()
        verify(session, never()).react(south, ReactionResponses.SKIP)

        clock.set(12_000L)
        coordinator.tick()
        verify(session).react(south, ReactionResponses.SKIP)
    }

    @Test
    fun `gb reaction window defaults every unanswered seat to skip`() {
        val clock = AtomicLong(10_000L)
        val session = baseSession(MahjongVariant.GB)
        val options = ReactionOptions(false, true, false, emptyList())
        `when`(session.players()).thenReturn(listOf(east, south))
        `when`(session.hasPendingReaction()).thenReturn(true)
        `when`(session.pendingReactionTileKey()).thenReturn("M5")
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.isReactionPending(east)).thenReturn(false)
        `when`(session.isReactionPending(south)).thenReturn(true)
        `when`(session.availableReactions(south)).thenReturn(options)
        `when`(session.react(south, ReactionResponses.SKIP)).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(8_000L)
        coordinator.tick()

        verify(session).react(south, ReactionResponses.SKIP)
        verify(session, never()).react(east, ReactionResponses.SKIP)
    }

    @Test
    fun `reaction accepted outside session wrapper still consumes shared extra time`() {
        val clock = AtomicLong(1_000L)
        val pending = AtomicBoolean(true)
        val session = baseSession(MahjongVariant.RIICHI)
        `when`(session.players()).thenReturn(listOf(south))
        `when`(session.hasPendingReaction()).thenReturn(true)
        `when`(session.pendingReactionTileKey()).thenReturn("M5")
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.isReactionPending(south)).thenAnswer { pending.get() }
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.set(6_000L)
        pending.set(false)
        coordinator.tick()

        assertEquals(3_000L, remainingExtraMillis(coordinator, south))
    }

    @Test
    fun `sichuan final four timeout never turns a rejected skip into ron`() {
        val clock = AtomicLong(12_000L)
        val session = baseSession(MahjongVariant.SICHUAN)
        val options = ReactionOptions(true, false, false, emptyList())
        `when`(session.remainingWallCount()).thenReturn(4)
        `when`(session.players()).thenReturn(listOf(south))
        `when`(session.hasPendingReaction()).thenReturn(true)
        `when`(session.pendingReactionTileKey()).thenReturn("P9")
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.isReactionPending(south)).thenReturn(true)
        `when`(session.availableReactions(south)).thenReturn(options)
        `when`(session.react(south, ReactionResponses.SKIP)).thenReturn(false)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(8_000L)
        coordinator.tick()

        verify(session).react(south, ReactionResponses.SKIP)
        verify(session, never()).react(south, ReactionResponses.RON)
    }

    @Test
    fun `sichuan ordinary wall never turns a rejected skip into ron`() {
        val clock = AtomicLong(14_000L)
        val session = baseSession(MahjongVariant.SICHUAN)
        val options = ReactionOptions(true, false, false, emptyList())
        `when`(session.remainingWallCount()).thenReturn(40)
        `when`(session.players()).thenReturn(listOf(south))
        `when`(session.hasPendingReaction()).thenReturn(true)
        `when`(session.pendingReactionTileKey()).thenReturn("S8")
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.isReactionPending(south)).thenReturn(true)
        `when`(session.availableReactions(south)).thenReturn(options)
        `when`(session.react(south, ReactionResponses.SKIP)).thenReturn(false)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(8_000L)
        coordinator.tick()

        verify(session).react(south, ReactionResponses.SKIP)
        verify(session, never()).react(south, ReactionResponses.RON)
    }

    @Test
    fun `sichuan exchange timeout chooses three tiles from deterministic least eligible suit`() {
        val clock = AtomicLong(2_000L)
        val session = baseSession(MahjongVariant.SICHUAN)
        `when`(session.players()).thenReturn(listOf(east))
        `when`(session.isSichuanExchangePhase(east)).thenReturn(true)
        `when`(session.hand(east)).thenReturn(
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.M4,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.S1,
                MahjongTile.S2,
                MahjongTile.S3,
                MahjongTile.S4,
                MahjongTile.S5,
                MahjongTile.S6,
                MahjongTile.S7,
            ),
        )
        `when`(session.submitSichuanExchangeSelection(east, listOf(4, 5, 6))).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(8_000L)
        coordinator.tick()

        verify(session).submitSichuanExchangeSelection(east, listOf(4, 5, 6))
    }

    @Test
    fun `sichuan dingque timeout deterministically chooses least represented suit`() {
        val clock = AtomicLong(3_000L)
        val session = baseSession(MahjongVariant.SICHUAN)
        `when`(session.players()).thenReturn(listOf(east))
        `when`(session.canChooseSichuanMissingSuit(east)).thenReturn(true)
        `when`(session.hand(east)).thenReturn(
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.M4,
                MahjongTile.M5,
            ),
        )
        `when`(session.chooseSichuanMissingSuit(east, "suo")).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(8_000L)
        coordinator.tick()

        verify(session).chooseSichuanMissingSuit(east, "suo")
    }

    @Test
    fun `viewing river leaves dingque deadline running`() {
        val clock = AtomicLong(3_000L)
        val session = baseSession(MahjongVariant.SICHUAN)
        `when`(session.players()).thenReturn(listOf(east))
        `when`(session.canChooseSichuanMissingSuit(east)).thenReturn(true)
        `when`(session.hand(east)).thenReturn(
            listOf(
                MahjongTile.M1,
                MahjongTile.M2,
                MahjongTile.M3,
                MahjongTile.P1,
                MahjongTile.P2,
                MahjongTile.P3,
                MahjongTile.P4,
                MahjongTile.P5,
                MahjongTile.P6,
                MahjongTile.P7,
                MahjongTile.P8,
                MahjongTile.P9,
                MahjongTile.M4,
                MahjongTile.M5,
            ),
        )
        `when`(session.chooseSichuanMissingSuit(east, "suo")).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(2_000L)
        assertEquals(6L, coordinator.secondsRemaining(east))

        // Entering the client-only river camera does not alter the action clock.
        clock.addAndGet(5_999L)
        coordinator.tick()

        verify(session, never()).chooseSichuanMissingSuit(east, "suo")
        assertEquals(1L, coordinator.secondsRemaining(east))

        clock.addAndGet(1L)
        coordinator.tick()
        verify(session).chooseSichuanMissingSuit(east, "suo")
    }

    @Test
    fun `new action window created while viewing river keeps its normal deadline`() {
        val clock = AtomicLong(3_000L)
        val dingquePending = AtomicBoolean(true)
        val session = turnSession(MahjongVariant.SICHUAN, east)
        `when`(session.canChooseSichuanMissingSuit(east)).thenAnswer { dingquePending.get() }
        `when`(session.canSelectHandTile(east, 13)).thenReturn(true)
        `when`(session.discard(east, 13)).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()

        // Another seat's action advances the table from dingque to this player's turn
        // while the player is still inspecting the river.
        dingquePending.set(false)
        clock.addAndGet(120_000L)
        coordinator.tick()
        assertEquals(60L, coordinator.secondsRemaining(east))

        clock.addAndGet(59_999L)
        coordinator.tick()
        verify(session, never()).discard(east, 13)
        assertEquals(1L, coordinator.secondsRemaining(east))

        clock.addAndGet(1L)
        coordinator.tick()
        verify(session).discard(east, 13)
    }

    @Test
    fun `new hand started while viewing river keeps its normal deadline`() {
        val clock = AtomicLong(3_000L)
        val round = AtomicInteger(0)
        val session = turnSession(MahjongVariant.RIICHI, east)
        `when`(session.roundIndex()).thenAnswer { round.get() }
        `when`(session.canSelectHandTile(east, 13)).thenReturn(true)
        `when`(session.discard(east, 13)).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(2_000L)
        assertEquals(58L, coordinator.secondsRemaining(east))

        round.incrementAndGet()
        coordinator.beginRound()
        assertEquals(60L, coordinator.secondsRemaining(east))

        clock.addAndGet(59_999L)
        coordinator.tick()
        verify(session, never()).discard(east, 13)
        assertEquals(1L, coordinator.secondsRemaining(east))

        clock.addAndGet(1L)
        coordinator.tick()
        verify(session).discard(east, 13)
    }

    @Test
    fun `sichuan final four timeout never declares tsumo for the player`() {
        val clock = AtomicLong(4_000L)
        val session = turnSession(MahjongVariant.SICHUAN, east)
        `when`(session.remainingWallCount()).thenReturn(4)
        `when`(session.gbCanWinByTsumo(east)).thenReturn(true)
        `when`(session.declareTsumo(east)).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(60_000L)
        coordinator.tick()

        verify(session, never()).declareTsumo(east)
        verify(session, never()).discard(eq(east), anyInt())
    }

    @Test
    fun `unattended current seat is delegated on next tick rather than waiting for deadline`() {
        val clock = AtomicLong(5_000L)
        val session = turnSession(MahjongVariant.RIICHI, east)
        `when`(session.isPlayerUnattended(east)).thenReturn(true)
        `when`(session.canSelectHandTile(east, 13)).thenReturn(true)
        `when`(session.discard(east, 13)).thenReturn(true)
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()

        verify(session).discard(east, 13)
    }

    @Test
    fun `accepted timeout action that finishes the round is not retried`() {
        val clock = AtomicLong(6_000L)
        val active = AtomicBoolean(true)
        val session = turnSession(MahjongVariant.RIICHI, east)
        `when`(session.isStarted()).thenAnswer { active.get() }
        `when`(session.canSelectHandTile(east, 13)).thenReturn(true)
        `when`(session.discard(east, 13)).thenAnswer {
            active.set(false)
            true
        }
        val coordinator = SessionActionDeadlineCoordinator(session, clock::get)

        coordinator.tick()
        clock.addAndGet(60_000L)
        coordinator.tick()
        coordinator.tick()

        verify(session).discard(east, 13)
    }

    private fun baseSession(variant: MahjongVariant): MahjongTableSession {
        val session = mock(MahjongTableSession::class.java)
        `when`(session.isStarted()).thenReturn(true)
        `when`(session.isRoundStartInProgress()).thenReturn(false)
        `when`(session.currentVariant()).thenReturn(variant)
        `when`(session.configuredRuleSnapshot()).thenReturn(
            MahjongRule(thinkingTime = MahjongRule.ThinkingTime.VERY_SHORT),
        )
        `when`(session.roundIndex()).thenReturn(0)
        `when`(session.remainingWallCount()).thenReturn(50)
        `when`(session.kanCount()).thenReturn(0)
        return session
    }

    private fun turnSession(
        variant: MahjongVariant,
        playerId: UUID,
    ): MahjongTableSession {
        val session = baseSession(variant)
        `when`(session.players()).thenReturn(listOf(playerId))
        `when`(session.hasPendingReaction()).thenReturn(false)
        `when`(session.currentSeat()).thenReturn(SeatWind.EAST)
        `when`(session.playerAt(SeatWind.EAST)).thenReturn(playerId)
        `when`(session.hand(playerId)).thenReturn(List(14) { MahjongTile.M1 })
        `when`(session.discards(playerId)).thenReturn(emptyList())
        return session
    }

    @Suppress("UNCHECKED_CAST")
    private fun remainingExtraMillis(
        coordinator: SessionActionDeadlineCoordinator,
        playerId: UUID,
    ): Long {
        val field = SessionActionDeadlineCoordinator::class.java.getDeclaredField("remainingExtraMillis")
        field.isAccessible = true
        return (field.get(coordinator) as Map<UUID, Long>).getValue(playerId)
    }
}
