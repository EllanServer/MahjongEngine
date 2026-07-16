package top.ellan.mahjong.render.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.snapshot.TableViewerActionButtonSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerPromptSnapshot;

final class ViewerOverlayRendererOverheadTest {
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000778");

    @Test
    void viewRiverEntryIsPinnedToTheViewersRight() {
        TableRenderSubject session = session();
        TableViewerActionButtonSnapshot button = button(TableViewerActionButtonSnapshot.Placement.RIGHT_SIDE);

        ViewerOverlayRenderer.ViewerActionOverlayPlan plan = ViewerOverlayRenderer.renderViewerActionOverlayPlan(
            session,
            overlay(button)
        );

        assertEquals(1, plan.entitySpecs().size());
        assertEquals(1, plan.flatInteractions().size());
        DisplayEntities.LabelSpec label = (DisplayEntities.LabelSpec) plan.entitySpecs().get(0);
        assertEquals(Display.Billboard.FIXED, label.billboard());
        assertTrue(label.location().getZ() < session.center().getZ());
    }

    @Test
    void seatedRequiredActionsCannotStealClicksFromTheRiverViewControl() {
        TableRenderSubject session = session();
        List<TableViewerActionButtonSnapshot> buttons = List.of(
            new TableViewerActionButtonSnapshot(
                "dingque-wan",
                "Missing characters",
                NamedTextColor.RED,
                "turn:dingque:wan",
                1.6F,
                TableViewerActionButtonSnapshot.Placement.ACTION_ROW
            ),
            new TableViewerActionButtonSnapshot(
                "dingque-tong",
                "Missing dots",
                NamedTextColor.GOLD,
                "turn:dingque:tong",
                1.2F,
                TableViewerActionButtonSnapshot.Placement.ACTION_ROW
            ),
            new TableViewerActionButtonSnapshot(
                "dingque-suo",
                "Missing bamboos",
                NamedTextColor.GREEN,
                "turn:dingque:suo",
                1.5F,
                TableViewerActionButtonSnapshot.Placement.ACTION_ROW
            ),
            button(TableViewerActionButtonSnapshot.Placement.RIGHT_SIDE)
        );

        ViewerOverlayRenderer.ViewerActionOverlayPlan plan = ViewerOverlayRenderer.renderViewerActionOverlayPlan(
            session,
            new TableViewerActionOverlaySnapshot(VIEWER_ID, "seated-actions", buttons, "fingerprint")
        );
        List<DisplayInteractionRayRegistry.RayInteraction> interactions = plan.flatInteractions();
        DisplayInteractionRayRegistry.RayInteraction river = interactions.stream()
            .filter(spec -> "view:river".equals(spec.action().command()))
            .findFirst()
            .orElseThrow();

        for (DisplayInteractionRayRegistry.RayInteraction decision : interactions) {
            if (decision == river) {
                continue;
            }
            double centerDistance = Math.hypot(
                river.centerX() - decision.centerX(),
                river.centerZ() - decision.centerZ()
            );
            double edgeGap = centerDistance - (river.width() + decision.width()) / 2.0D;
            assertTrue(
                edgeGap + 1.0E-6D >= TableRenderConstants.VIEWER_PINNED_ACTION_GAP,
                "River-view hitbox is too close to " + decision.action().command()
            );
        }
    }

    @Test
    void returnSeatControlMovesToTheReachableOverheadCenter() {
        TableRenderSubject session = session();
        TableViewerActionButtonSnapshot button = button(TableViewerActionButtonSnapshot.Placement.OVERHEAD_CENTER);

        ViewerOverlayRenderer.ViewerActionOverlayPlan plan = ViewerOverlayRenderer.renderViewerActionOverlayPlan(
            session,
            overlay(button)
        );
        List<DisplayEntities.EntitySpec> specs = plan.entitySpecs();

        assertEquals(1, specs.size());
        DisplayEntities.LabelSpec label = (DisplayEntities.LabelSpec) specs.get(0);
        assertEquals(Display.Billboard.CENTER, label.billboard());
        assertEquals(session.center().getX(), label.location().getX());
        assertEquals(session.center().getZ(), label.location().getZ());
        assertEquals(session.center().getY() + PluginSettings.defaults().tables().overheadView().height() / 2.0D, label.location().getY());
        assertTrue(plan.flatInteractions().isEmpty());
    }

    @Test
    void overheadDecisionControlsAndReturnSeatStayVisibleWithoutOverlapping() {
        TableRenderSubject session = session();
        List<TableViewerActionButtonSnapshot> buttons = List.of(
            overheadButton("ron", "Ron", "reaction:ron"),
            overheadButton("pon", "Pon", "reaction:pon"),
            overheadButton("minkan", "Open kan", "reaction:minkan"),
            overheadButton("chii", "Chii", "reaction:chii:0"),
            overheadButton("skip", "Skip", "reaction:skip"),
            new TableViewerActionButtonSnapshot(
                "view-river",
                "Return to seat",
                NamedTextColor.GREEN,
                "view:river",
                1.2F,
                TableViewerActionButtonSnapshot.Placement.OVERHEAD_CENTER
            )
        );

        ViewerOverlayRenderer.ViewerActionOverlayPlan plan = ViewerOverlayRenderer.renderViewerActionOverlayPlan(
            session,
            new TableViewerActionOverlaySnapshot(VIEWER_ID, "overhead-decisions", buttons, "fingerprint")
        );
        List<DisplayEntities.EntitySpec> specs = plan.entitySpecs();
        List<DisplayEntities.LabelSpec> labels = specs.stream()
            .filter(DisplayEntities.LabelSpec.class::isInstance)
            .map(DisplayEntities.LabelSpec.class::cast)
            .toList();

        assertEquals(buttons.size(), labels.size());
        assertEquals(buttons.size(), specs.size());
        assertTrue(plan.flatInteractions().isEmpty());
        assertTrue(labels.stream().allMatch(label -> label.billboard() == Display.Billboard.CENTER));
        assertEquals(1L, labels.stream().map(label -> label.location().getY()).distinct().count());
    }

    @Test
    void fixedButtonsWithoutASeatStillUseRayInteractionsInsteadOfBukkitInteractions() {
        TableRenderSubject session = session();
        when(session.seatOf(VIEWER_ID)).thenReturn(null);

        ViewerOverlayRenderer.ViewerActionOverlayPlan plan = ViewerOverlayRenderer.renderViewerActionOverlayPlan(
            session,
            overlay(button(TableViewerActionButtonSnapshot.Placement.ACTION_ROW))
        );

        assertEquals(1, plan.entitySpecs().size());
        assertTrue(plan.entitySpecs().get(0) instanceof DisplayEntities.LabelSpec);
        assertEquals(1, plan.flatInteractions().size());
        assertEquals(1.0D, plan.flatInteractions().get(0).acrossX());
        assertEquals(0.0D, plan.flatInteractions().get(0).acrossZ());
    }

    @Test
    void seatedPlayerDropsOnlyTheDuplicateDetailedOverlay() {
        TableRenderSubject session = session();
        when(session.isStarted()).thenReturn(true);
        TableViewerPromptSnapshot prompt = new TableViewerPromptSnapshot(
            VIEWER_ID,
            "viewer-prompt:" + VIEWER_ID,
            true,
            Component.text("Choose an action"),
            "prompt"
        );
        TableViewerActionOverlaySnapshot actions = overlay(button(TableViewerActionButtonSnapshot.Placement.RIGHT_SIDE));
        TableViewerOverlaySnapshot snapshot = new TableViewerOverlaySnapshot(
            VIEWER_ID,
            "viewer-overlay:" + VIEWER_ID,
            false,
            Component.text("duplicate persistent details"),
            prompt,
            actions,
            List.of(),
            "overlay"
        );

        assertTrue(ViewerOverlayRenderer.renderViewerOverlaySpecs(session, snapshot).isEmpty());
        assertEquals(1, ViewerOverlayRenderer.renderViewerPromptSpecs(session, prompt).size());
        ViewerOverlayRenderer.ViewerActionOverlayPlan actionPlan = ViewerOverlayRenderer.renderViewerActionOverlayPlan(
            session,
            actions
        );
        assertEquals(1, actionPlan.entitySpecs().size());
        assertEquals(1, actionPlan.flatInteractions().size());
    }

    private static TableRenderSubject session() {
        TableRenderSubject session = mock(TableRenderSubject.class);
        when(session.center()).thenReturn(new Location(null, 100.0D, 64.0D, 200.0D));
        when(session.seatOf(VIEWER_ID)).thenReturn(SeatWind.EAST);
        when(session.settings()).thenReturn(PluginSettings.defaults());
        when(session.id()).thenReturn("overhead-layout-test");
        return session;
    }

    private static TableViewerActionOverlaySnapshot overlay(TableViewerActionButtonSnapshot button) {
        return new TableViewerActionOverlaySnapshot(VIEWER_ID, "overhead-layout", List.of(button), "fingerprint");
    }

    private static TableViewerActionButtonSnapshot button(TableViewerActionButtonSnapshot.Placement placement) {
        return new TableViewerActionButtonSnapshot(
            "view-river",
            placement == TableViewerActionButtonSnapshot.Placement.OVERHEAD_CENTER ? "Return to seat" : "View river",
            NamedTextColor.AQUA,
            "view:river",
            1.2F,
            placement
        );
    }

    private static TableViewerActionButtonSnapshot overheadButton(String id, String label, String command) {
        return new TableViewerActionButtonSnapshot(
            id,
            label,
            NamedTextColor.YELLOW,
            command,
            0.8F,
            TableViewerActionButtonSnapshot.Placement.OVERHEAD_CENTER
        );
    }
}
