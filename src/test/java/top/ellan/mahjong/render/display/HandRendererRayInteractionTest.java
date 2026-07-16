package top.ellan.mahjong.render.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.scene.HandRenderer;
import top.ellan.mahjong.render.scene.TableGeometry;
import top.ellan.mahjong.render.scene.TableRenderConstants;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;

final class HandRendererRayInteractionTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");

    @Test
    void everySeatUsesAnExactModelSizedOrientedHitBox() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        TableRenderSubject session = mock(TableRenderSubject.class);
        when(session.center()).thenReturn(new Location(world, 0.0D, 0.0D, 0.0D));
        when(session.id()).thenReturn("table-a");
        when(session.currentVariant()).thenReturn(MahjongVariant.RIICHI);

        for (SeatWind wind : SeatWind.values()) {
            TableSeatRenderSnapshot seat = mock(TableSeatRenderSnapshot.class);
            TableRenderLayout.SeatLayoutPlan plan = mock(TableRenderLayout.SeatLayoutPlan.class);
            when(seat.playerId()).thenReturn(PLAYER_ID);
            when(seat.wind()).thenReturn(wind);
            when(seat.hand()).thenReturn(List.of(MahjongTile.M1));
            when(plan.privateHandPoints()).thenReturn(List.of(new TableRenderLayout.Point(4.0D, 65.0D, 8.0D)));
            when(plan.yaw()).thenReturn(TableGeometry.seatYaw(wind));

            HandRenderer.HandTileRenderPlan renderPlan = HandRenderer.renderHandPrivateTilePlan(
                session,
                seat,
                plan,
                0
            );
            DisplayInteractionRayRegistry.RayInteraction interaction = renderPlan.rayInteractions().get(0);
            TableGeometry.Offset across = TableGeometry.offsetAcrossSeat(wind, 1.0D);
            double normalX = -across.z();
            double normalZ = across.x();

            assertEquals(1, renderPlan.entitySpecs().size());
            assertEquals(DisplayEntities.TileDisplaySpec.class, renderPlan.entitySpecs().get(0).getClass());
            assertEquals(1, renderPlan.rayInteractions().size());
            assertEquals(TableRenderConstants.TILE_WIDTH, interaction.width(), 1.0E-6D);
            assertEquals(TableRenderConstants.TILE_HEIGHT, interaction.height(), 1.0E-6D);
            assertEquals(TableRenderConstants.TILE_DEPTH, interaction.depth(), 1.0E-6D);
            assertEquals(
                DisplayClickAction.handTile("table-a", PLAYER_ID, 0),
                DisplayInteractionRayRegistry.resolveRay(
                    List.of(interaction),
                    WORLD_ID,
                    interaction.centerX() - normalX,
                    interaction.centerY(),
                    interaction.centerZ() - normalZ,
                    normalX,
                    0.0D,
                    normalZ,
                    2.0D
                )
            );
        }
    }

    @Test
    void publicHandNeverContainsTheOwnersTileIdentityBeforeStartedFlag() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        TableRenderSubject session = mock(TableRenderSubject.class);
        when(session.center()).thenReturn(new Location(world, 0.0D, 0.0D, 0.0D));
        when(session.currentVariant()).thenReturn(MahjongVariant.RIICHI);
        top.ellan.mahjong.render.snapshot.TableRenderSnapshot snapshot = mock(
            top.ellan.mahjong.render.snapshot.TableRenderSnapshot.class
        );
        when(snapshot.started()).thenReturn(false);
        TableSeatRenderSnapshot seat = mock(TableSeatRenderSnapshot.class);
        when(seat.playerId()).thenReturn(PLAYER_ID);
        when(seat.hand()).thenReturn(List.of(MahjongTile.M1));
        TableRenderLayout.SeatLayoutPlan plan = mock(TableRenderLayout.SeatLayoutPlan.class);
        when(plan.publicHandPoints()).thenReturn(List.of(new TableRenderLayout.Point(1.0D, 2.0D, 3.0D)));

        DisplayEntities.TileDisplaySpec publicSpec = (DisplayEntities.TileDisplaySpec) HandRenderer
            .renderHandPublicTileSpecs(session, snapshot, seat, plan, 0)
            .get(0);

        assertEquals(MahjongTile.UNKNOWN, publicSpec.tile());
        assertEquals(null, publicSpec.privateViewers());
        assertEquals(List.of(PLAYER_ID), publicSpec.hiddenViewers());
    }
}
