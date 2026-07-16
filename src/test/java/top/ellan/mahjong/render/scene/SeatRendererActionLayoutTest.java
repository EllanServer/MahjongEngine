package top.ellan.mahjong.render.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;

final class SeatRendererActionLayoutTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000779");

    @Test
    void pairedSeatActionsKeepTheirMeasuredWidthsApartInEverySupportedLocaleAndDirection() {
        for (Locale locale : List.of(
            Locale.ENGLISH,
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.JAPANESE
        )) {
            for (SeatWind wind : SeatWind.values()) {
                List<DisplayInteractionRayRegistry.RayInteraction> actions = actionInteractions(locale, wind);

                assertEquals(2, actions.size(), locale + " " + wind);
                DisplayInteractionRayRegistry.RayInteraction readyAction = actions.get(0);
                DisplayInteractionRayRegistry.RayInteraction leaveAction = actions.get(1);
                double centerDistance = Math.hypot(
                    readyAction.centerX() - leaveAction.centerX(),
                    readyAction.centerZ() - leaveAction.centerZ()
                );
                double requiredDistance = (readyAction.width() + leaveAction.width()) / 2.0D
                    + TableRenderConstants.SEAT_SIDE_ACTION_GAP;

                assertEquals(requiredDistance, centerDistance, 1.0E-6D, locale + " " + wind);
            }
        }
    }

    @Test
    void chineseUnreadyLabelUsesWideGlyphMeasurement() {
        List<DisplayInteractionRayRegistry.RayInteraction> actions = actionInteractions(Locale.SIMPLIFIED_CHINESE, SeatWind.EAST);

        assertTrue(actions.get(0).width() >= 1.1F, "取消准备 must not be measured as narrow ASCII text");
    }

    @Test
    void emptySeatJoinLabelUsesOneTextDisplayAndOnePublicRayPlane() {
        SeatWind wind = SeatWind.NORTH;
        TableRenderSubject session = mock(TableRenderSubject.class);
        when(session.center()).thenAnswer(ignored -> new Location(null, 100.0D, 64.0D, 200.0D));
        when(session.id()).thenReturn("seat-action-layout-test");
        when(session.publicLocale()).thenReturn(Locale.ENGLISH);
        when(session.messages()).thenReturn(new MessageService());
        TableSeatRenderSnapshot seat = new TableSeatRenderSnapshot(
            wind,
            null,
            "",
            "Empty",
            0,
            false,
            false,
            false,
            false,
            PLAYER_ID.toString(),
            -1,
            List.of(),
            -1,
            0,
            List.of(PLAYER_ID),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
        TableRenderLayout.Point origin = new TableRenderLayout.Point(100.0D, 64.0D, 200.0D);
        TableRenderLayout.SeatLayoutPlan seatPlan = new TableRenderLayout.SeatLayoutPlan(
            wind,
            origin,
            origin,
            origin,
            origin,
            0.0F,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        SeatRenderer.SeatLabelRenderPlan renderPlan = SeatRenderer.renderSeatLabelPlan(session, seat, seatPlan);

        assertEquals(2, renderPlan.entitySpecs().size());
        assertTrue(renderPlan.entitySpecs().stream().allMatch(DisplayEntities.LabelSpec.class::isInstance));
        assertEquals(1, renderPlan.rayInteractions().get(PLAYER_ID).size());
        assertEquals(
            DisplayClickAction.ActionType.JOIN_SEAT,
            renderPlan.rayInteractions().get(PLAYER_ID).get(0).action().actionType()
        );
        assertEquals(1, renderPlan.publicJoinInteractions().size());
        assertEquals(1, renderPlan.publicJoinBindings().get(0).specIndex());
        assertTrue(
            renderPlan.entitySpecs().get(renderPlan.publicJoinBindings().get(0).specIndex())
                instanceof DisplayEntities.LabelSpec
        );
        assertEquals(
            DisplayClickAction.ActionType.JOIN_SEAT,
            renderPlan.publicJoinInteractions().get(0).action().actionType()
        );
    }

    private static List<DisplayInteractionRayRegistry.RayInteraction> actionInteractions(Locale locale, SeatWind wind) {
        TableRenderSubject session = mock(TableRenderSubject.class);
        when(session.center()).thenAnswer(ignored -> new Location(null, 100.0D, 64.0D, 200.0D));
        when(session.id()).thenReturn("seat-action-layout-test");
        when(session.publicLocale()).thenReturn(locale);
        when(session.messages()).thenReturn(new MessageService());
        when(session.playerAt(wind)).thenReturn(PLAYER_ID);

        TableSeatRenderSnapshot seat = new TableSeatRenderSnapshot(
            wind,
            PLAYER_ID,
            "Arbousier",
            "Ready",
            25_000,
            false,
            true,
            false,
            true,
            "membership",
            -1,
            List.of(),
            -1,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
        TableRenderLayout.Point origin = new TableRenderLayout.Point(100.0D, 64.0D, 200.0D);
        TableRenderLayout.SeatLayoutPlan plan = new TableRenderLayout.SeatLayoutPlan(
            wind,
            origin,
            origin,
            origin,
            origin,
            0.0F,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        SeatRenderer.SeatLabelRenderPlan renderPlan = SeatRenderer.renderSeatLabelPlan(session, seat, plan);
        assertEquals(3, renderPlan.entitySpecs().size());
        assertTrue(renderPlan.entitySpecs().stream().allMatch(DisplayEntities.LabelSpec.class::isInstance));
        return renderPlan.rayInteractions().get(PLAYER_ID);
    }
}
