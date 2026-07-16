package top.ellan.mahjong.render.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;

final class CenterLabelRendererGravityTest {
    @Test
    void highlightedDiscardFloatsUprightAndFacesEachViewer() {
        World world = mock(World.class);
        TableRenderSubject session = mock(TableRenderSubject.class);
        TableRenderSnapshot snapshot = mock(TableRenderSnapshot.class);
        TableRenderLayout.LayoutPlan plan = mock(TableRenderLayout.LayoutPlan.class);
        double tableSurfaceY = 64.52D;

        when(session.center()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));
        when(session.currentVariant()).thenReturn(MahjongVariant.RIICHI);
        when(snapshot.publicCenterText()).thenReturn("");
        when(snapshot.lastPublicDiscardTile()).thenReturn(MahjongTile.M1);
        when(plan.displayCenter()).thenReturn(new TableRenderLayout.Point(0.0D, tableSurfaceY, 0.0D));

        List<DisplayEntities.EntitySpec> specs = CenterLabelRenderer.renderCenterLabelSpecs(session, snapshot, plan);
        DisplayEntities.TileDisplaySpec tile = (DisplayEntities.TileDisplaySpec) specs.getFirst();

        assertEquals(DisplayEntities.TileRenderPose.STANDING, tile.pose());
        assertEquals(Display.Billboard.VERTICAL, tile.billboard());
        assertEquals(TableRenderConstants.CENTER_LAST_DISCARD_TILE_SCALE, tile.scale());
        double renderedBottom = tile.location().getY()
            - (TableRenderConstants.TILE_HEIGHT * tile.scale() / 2.0D);
        assertTrue(renderedBottom > tableSurfaceY);
        assertEquals(
            tableSurfaceY + TableRenderConstants.CENTER_LAST_DISCARD_TILE_Y_OFFSET,
            tile.location().getY(),
            1.0E-9D
        );
    }
}
