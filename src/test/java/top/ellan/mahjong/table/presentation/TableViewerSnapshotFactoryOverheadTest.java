package top.ellan.mahjong.table.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kotlin.Pair;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.snapshot.TableViewerActionBarSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionButtonSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerHudPresentationSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.riichi.ReactionOptions;
import top.ellan.mahjong.riichi.model.MahjongTile;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.TableOverheadViews;
import top.ellan.mahjong.table.core.TableRuntimeServices;
import top.ellan.mahjong.table.core.TableSessionMutator;

final class TableViewerSnapshotFactoryOverheadTest {
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000777");

    @Test
    void activeRoundAddsViewRiverButtonWhenNoTurnOrReactionActionsExist() {
        for (MahjongVariant variant : List.of(MahjongVariant.RIICHI, MahjongVariant.GB, MahjongVariant.SICHUAN)) {
            Fixture fixture = fixture(false, true, SeatWind.SOUTH, false, variant);

            TableViewerActionButtonSnapshot button = onlyButton(fixture.snapshot());

            assertEquals("view-river", button.actionId());
            assertEquals("View river", button.label());
            assertEquals(NamedTextColor.AQUA, button.color());
            assertEquals("view:river", button.command());
            assertEquals(TableViewerActionButtonSnapshot.Placement.RIGHT_SIDE, button.placement());
            assertTrue(button.hitboxWidth() >= 0.7F);
        }
    }

    @Test
    void commandStateSummaryIncludesTheLiveRoundTurnWallAndSpectatorCount() {
        Fixture fixture = fixture(false, true, SeatWind.SOUTH, false, MahjongVariant.RIICHI);

        String summary = PlainTextComponentSerializer.plainText().serialize(
            new TableViewerSnapshotFactory(fixture.session()).createStateSummary(fixture.viewer())
        );

        assertEquals("Round East 1 | Turn Other player | Wall 42 | Spectators 0", summary);
    }

    @Test
    void activeOverheadViewIsReadOnlyAndUsesShiftToReturn() {
        Fixture fixture = fixture(true);

        assertTrue(fixture.snapshot().actions().actionButtons().isEmpty());
        assertFalse(fixture.snapshot().prompt().visible());
    }

    @Test
    void activeOverheadViewHidesCurrentDecisionsUntilThePlayerReturnsToTheSeat() {
        Fixture fixture = fixture(true, true, SeatWind.EAST, true);

        assertTrue(fixture.snapshot().actions().actionButtons().isEmpty());
        assertFalse(fixture.snapshot().prompt().visible());
    }

    @Test
    void returningFromOverheadRebuildsDingQueThatBecameAvailableWhileViewingTheRiver() {
        Fixture fixture = fixture(false, true, SeatWind.SOUTH, false, MahjongVariant.SICHUAN);
        TableOverheadViews overheadViews = fixture.session().plugin().tableManager().overheadViews();

        TableViewerOverlaySnapshot before = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());
        when(overheadViews.isActive(VIEWER_ID)).thenReturn(true);
        TableViewerOverlaySnapshot overheadBeforeDecision = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());
        when(fixture.session().canChooseSichuanMissingSuit(VIEWER_ID)).thenReturn(true);
        TableViewerOverlaySnapshot overheadAfterDecision = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());
        when(overheadViews.isActive(VIEWER_ID)).thenReturn(false);
        TableViewerOverlaySnapshot restored = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());

        List<String> expectedCommands = List.of(
            "turn:dingque:wan",
            "turn:dingque:tong",
            "turn:dingque:suo",
            "view:river"
        );
        assertEquals(
            List.of("view:river"),
            before.actions().actionButtons().stream().map(TableViewerActionButtonSnapshot::command).toList()
        );
        assertTrue(overheadBeforeDecision.actions().actionButtons().isEmpty());
        assertTrue(overheadAfterDecision.actions().actionButtons().isEmpty());
        assertEquals(
            expectedCommands,
            restored.actions().actionButtons().stream().map(TableViewerActionButtonSnapshot::command).toList()
        );
        assertTrue(restored.prompt().visible());
    }

    @Test
    void returningFromOverheadRebuildsPonAndChiiThatBecameAvailableWhileViewingTheRiver() {
        Fixture fixture = fixture(false, true, SeatWind.SOUTH, false, MahjongVariant.RIICHI);
        TableOverheadViews overheadViews = fixture.session().plugin().tableManager().overheadViews();
        TableViewerOverlaySnapshot before = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());

        when(overheadViews.isActive(VIEWER_ID)).thenReturn(true);
        when(fixture.session().hasPendingReaction()).thenReturn(true);
        when(fixture.session().availableReactions(VIEWER_ID)).thenReturn(
            new ReactionOptions(
                false,
                true,
                false,
                List.of(new Pair<>(MahjongTile.M2, MahjongTile.M3))
            )
        );
        TableViewerOverlaySnapshot overhead = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());

        when(overheadViews.isActive(VIEWER_ID)).thenReturn(false);
        TableViewerOverlaySnapshot restored = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());

        assertEquals(
            List.of("view:river"),
            before.actions().actionButtons().stream().map(TableViewerActionButtonSnapshot::command).toList()
        );
        assertTrue(overhead.actions().actionButtons().isEmpty());
        assertEquals(
            List.of("react:pon", "react:chii:m2:m3", "react:skip", "view:river"),
            restored.actions().actionButtons().stream().map(TableViewerActionButtonSnapshot::command).toList()
        );
        assertTrue(restored.prompt().visible());
    }

    @Test
    void activeOverheadViewKeepsPollingTheLiveActionDeadline() {
        Fixture fixture = fixture(true, true, SeatWind.EAST, false, MahjongVariant.RIICHI);
        when(fixture.session().actionDeadlineSecondsRemaining(VIEWER_ID)).thenReturn(12L, 11L);

        TableViewerActionBarSnapshot first = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerHudPresentationSnapshot(Locale.ENGLISH, VIEWER_ID)
            .actionBar();
        TableViewerActionBarSnapshot second = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerHudPresentationSnapshot(Locale.ENGLISH, VIEWER_ID)
            .actionBar();

        assertTrue(first.visible());
        assertEquals(
            "Auto-discard in 12s",
            PlainTextComponentSerializer.plainText().serialize(first.message())
        );
        assertTrue(second.visible());
        assertEquals(
            "Auto-discard in 11s",
            PlainTextComponentSerializer.plainText().serialize(second.message())
        );
        verify(fixture.session(), times(2)).actionDeadlineSecondsRemaining(VIEWER_ID);
    }

    @Test
    void entryIsHiddenWhenThePlayerIsNotSeatedInTheVehicle() {
        Fixture fixture = fixture(false, false);

        assertTrue(fixture.snapshot().actions().actionButtons().isEmpty());
    }

    @Test
    void seatedPlayerKeepsDecisionButtonsPromptAndCompactHud() {
        Fixture fixture = fixture(false, true, SeatWind.EAST, true);

        assertTrue(fixture.snapshot().prompt().visible());
        assertEquals(
            "Your turn",
            PlainTextComponentSerializer.plainText().serialize(fixture.snapshot().prompt().prompt())
        );
        assertTrue(fixture.snapshot().actions().actionButtons().stream().anyMatch(button -> button.label().equals("Tsumo")));
        assertTrue(fixture.snapshot().actions().actionButtons().stream().anyMatch(button -> button.actionId().equals("view-river")));

        String hud = PlainTextComponentSerializer.plainText().serialize(
            new TableViewerSnapshotFactory(fixture.session()).captureViewerHudSnapshot(Locale.ENGLISH, VIEWER_ID).title()
        );
        assertEquals("East 1 | Turn Other player | Wall 42", hud);
    }

    @Test
    void actionBarShowsTheCorrectAutomaticActionForEveryInteractivePhase() {
        Fixture turn = fixture(false, true, SeatWind.EAST, false, MahjongVariant.RIICHI);
        when(turn.session().actionDeadlineSecondsRemaining(VIEWER_ID)).thenReturn(7L);
        assertActionBar(turn.session(), "Auto-discard in 7s");

        Fixture reaction = fixture(false, true, SeatWind.SOUTH, false, MahjongVariant.GB);
        when(reaction.session().hasPendingReaction()).thenReturn(true);
        when(reaction.session().availableReactions(VIEWER_ID)).thenReturn(
            new ReactionOptions(false, false, false, List.of())
        );
        when(reaction.session().actionDeadlineSecondsRemaining(VIEWER_ID)).thenReturn(6L);
        assertActionBar(reaction.session(), "Auto-skip in 6s");

        Fixture exchange = fixture(false, true, SeatWind.SOUTH, false, MahjongVariant.SICHUAN);
        when(exchange.session().isSichuanExchangePhase(VIEWER_ID)).thenReturn(true);
        when(exchange.session().actionDeadlineSecondsRemaining(VIEWER_ID)).thenReturn(5L);
        assertActionBar(exchange.session(), "Auto-exchange in 5s");

        Fixture dingQue = fixture(false, true, SeatWind.SOUTH, false, MahjongVariant.SICHUAN);
        when(dingQue.session().canChooseSichuanMissingSuit(VIEWER_ID)).thenReturn(true);
        when(dingQue.session().actionDeadlineSecondsRemaining(VIEWER_ID)).thenReturn(4L);
        assertActionBar(dingQue.session(), "Auto-select missing suit in 4s");
    }

    @Test
    void hudAndActionBarPollDoesNotEnumerateFullPlayerActions() {
        Fixture fixture = fixture(false, true, SeatWind.EAST, true, MahjongVariant.RIICHI);
        when(fixture.session().actionDeadlineSecondsRemaining(VIEWER_ID)).thenReturn(9L);
        clearInvocations(fixture.session());

        TableViewerHudPresentationSnapshot snapshot = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerHudPresentationSnapshot(Locale.ENGLISH, VIEWER_ID);

        assertTrue(snapshot.actionBar().visible());
        verify(fixture.session(), never()).viewerActionMenuState(VIEWER_ID);
        verify(fixture.session(), never()).canDeclareTsumo(VIEWER_ID);
        verify(fixture.session(), never()).canDeclareRiichi(VIEWER_ID);
        verify(fixture.session(), never()).canDeclareConcealedKan(VIEWER_ID);
        verify(fixture.session(), never()).canDeclareAddedKan(VIEWER_ID);
        verify(fixture.session(), never()).canDeclareFlower(VIEWER_ID);
        verify(fixture.session(), never()).suggestedDiscardSuggestions(VIEWER_ID);
        verify(fixture.session(), never()).suggestedRiichiIndices(VIEWER_ID);
        verify(fixture.session(), never()).suggestedFlowerIndices(VIEWER_ID);
        verify(fixture.session(), never()).suggestedConcealedKanTiles(VIEWER_ID);
        verify(fixture.session(), never()).suggestedAddedKanTiles(VIEWER_ID);
    }

    @Test
    void mainOverlayFingerprintIgnoresActionButtonsRenderedInTheirOwnRegion() {
        Fixture fixture = fixture(false, true, SeatWind.EAST, false, MahjongVariant.RIICHI);
        TableViewerOverlaySnapshot withoutTsumo = fixture.snapshot();
        when(fixture.session().canDeclareTsumo(VIEWER_ID)).thenReturn(true);

        TableViewerOverlaySnapshot withTsumo = new TableViewerSnapshotFactory(fixture.session())
            .captureViewerOverlaySnapshot(fixture.viewer());

        assertEquals(withoutTsumo.fingerprint(), withTsumo.fingerprint());
        assertNotEquals(withoutTsumo.actions().fingerprint(), withTsumo.actions().fingerprint());
    }

    private static Fixture fixture(boolean overheadActive) {
        return fixture(overheadActive, true);
    }

    private static Fixture fixture(boolean overheadActive, boolean insideVehicle) {
        return fixture(overheadActive, insideVehicle, SeatWind.SOUTH, false);
    }

    private static Fixture fixture(
        boolean overheadActive,
        boolean insideVehicle,
        SeatWind currentSeat,
        boolean canTsumo
    ) {
        return fixture(overheadActive, insideVehicle, currentSeat, canTsumo, MahjongVariant.RIICHI);
    }

    private static Fixture fixture(
        boolean overheadActive,
        boolean insideVehicle,
        SeatWind currentSeat,
        boolean canTsumo,
        MahjongVariant variant
    ) {
        TableSessionMutator session = mock(TableSessionMutator.class);
        TableRuntimeServices plugin = mock(TableRuntimeServices.class);
        MahjongTableManager tableManager = mock(MahjongTableManager.class);
        TableOverheadViews overheadViews = mock(TableOverheadViews.class);
        Player viewer = mock(Player.class);
        MessageService messages = new MessageService();

        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(viewer.locale()).thenReturn(Locale.ENGLISH);
        when(viewer.isInsideVehicle()).thenReturn(insideVehicle);
        when(session.plugin()).thenReturn(plugin);
        when(plugin.messages()).thenReturn(messages);
        when(plugin.settings()).thenReturn(PluginSettings.defaults());
        when(plugin.tableManager()).thenReturn(tableManager);
        when(tableManager.overheadViews()).thenReturn(overheadViews);
        when(overheadViews.isActive(VIEWER_ID)).thenReturn(overheadActive);
        when(overheadViews.isAvailable()).thenReturn(true);

        when(session.hasRoundController()).thenReturn(true);
        when(session.isStarted()).thenReturn(true);
        when(session.isSpectator(VIEWER_ID)).thenReturn(false);
        when(session.seatOf(VIEWER_ID)).thenReturn(SeatWind.EAST);
        // A different current seat produces a WAITING action snapshot with no normal action buttons.
        when(session.currentSeat()).thenReturn(currentSeat);
        when(session.isCurrentPlayer(VIEWER_ID)).thenReturn(currentSeat == SeatWind.EAST);
        when(session.canDeclareTsumo(VIEWER_ID)).thenReturn(canTsumo);
        when(session.currentVariant()).thenReturn(variant);
        when(session.id()).thenReturn("overhead-test");
        when(session.waitingDisplaySummary(Locale.ENGLISH)).thenReturn("");
        when(session.ruleDisplaySummary(Locale.ENGLISH)).thenReturn("");
        when(session.roundDisplay(Locale.ENGLISH)).thenReturn("East 1");
        when(session.roundDisplay()).thenReturn("East 1");
        when(session.dealerName(Locale.ENGLISH)).thenReturn("Dealer");
        when(session.currentTurnDisplayName()).thenReturn("Other player");
        when(session.remainingWallCount()).thenReturn(42);
        when(session.doraIndicators()).thenReturn(List.of());
        when(session.suggestedDiscardSuggestions(VIEWER_ID)).thenReturn(List.of());

        TableViewerOverlaySnapshot snapshot = new TableViewerSnapshotFactory(session).captureViewerOverlaySnapshot(viewer);
        return new Fixture(session, viewer, snapshot);
    }

    private static void assertActionBar(TableSessionMutator session, String expected) {
        TableViewerActionBarSnapshot snapshot = new TableViewerSnapshotFactory(session)
            .captureViewerActionBarSnapshot(Locale.ENGLISH, VIEWER_ID);
        assertTrue(snapshot.visible());
        assertEquals(expected, PlainTextComponentSerializer.plainText().serialize(snapshot.message()));
    }

    private static TableViewerActionButtonSnapshot onlyButton(TableViewerOverlaySnapshot snapshot) {
        List<TableViewerActionButtonSnapshot> buttons = snapshot.actions().actionButtons();
        assertEquals(1, buttons.size());
        return buttons.get(0);
    }

    private record Fixture(TableSessionMutator session, Player viewer, TableViewerOverlaySnapshot snapshot) {
    }
}
