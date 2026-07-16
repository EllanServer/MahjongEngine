package top.ellan.mahjong.render.scene;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;

/**
 * Renders the center table label and the highlighted last-discard tile.
 */
public final class CenterLabelRenderer {
    private CenterLabelRenderer() {
    }

    public static List<Entity> renderCenterLabel(TableRenderSubject session) {
        Location center = TableGeometry.displayCenter(session);
        List<Entity> spawned = new ArrayList<>(2);
        String centerText = session.publicCenterText();
        if (centerText != null && !centerText.isBlank()) {
            spawned.add(DisplayEntities.spawnLabel(
                session.bukkitPlugin(),
                center.clone().add(0.0D, TableRenderConstants.CENTER_LABEL_Y_OFFSET, 0.0D),
                Component.text(centerText),
                TableRenderConstants.CENTER_LABEL_BACKGROUND
            ));
        }
        if (session.lastPublicDiscardTile() != null) {
            spawned.add(spawnCenterLastDiscardTile(session, center, session.lastPublicDiscardTile()));
        }
        return List.copyOf(spawned);
    }

    public static List<Entity> renderCenterLabel(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableRenderLayout.LayoutPlan plan
    ) {
        Location center = TableGeometry.toLocation(session, plan.displayCenter());
        List<Entity> spawned = new ArrayList<>(2);
        if (snapshot.publicCenterText() != null && !snapshot.publicCenterText().isBlank()) {
            spawned.add(DisplayEntities.spawnLabel(
                session.bukkitPlugin(),
                center.clone().add(0.0D, TableRenderConstants.CENTER_LABEL_Y_OFFSET, 0.0D),
                Component.text(snapshot.publicCenterText()),
                TableRenderConstants.CENTER_LABEL_BACKGROUND
            ));
        }
        if (snapshot.lastPublicDiscardTile() != null) {
            spawned.add(spawnCenterLastDiscardTile(session, center, snapshot.lastPublicDiscardTile()));
        }
        return List.copyOf(spawned);
    }

    public static List<DisplayEntities.EntitySpec> renderCenterLabelSpecs(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableRenderLayout.LayoutPlan plan
    ) {
        Location center = TableGeometry.toLocation(session, plan.displayCenter());
        List<DisplayEntities.EntitySpec> specs = new ArrayList<>(2);
        if (snapshot.publicCenterText() != null && !snapshot.publicCenterText().isBlank()) {
            specs.add(DisplayEntities.labelSpec(
                center.clone().add(0.0D, TableRenderConstants.CENTER_LABEL_Y_OFFSET, 0.0D),
                Component.text(snapshot.publicCenterText()),
                TableRenderConstants.CENTER_LABEL_BACKGROUND
            ));
        }
        if (snapshot.lastPublicDiscardTile() != null) {
            specs.add(DisplayEntities.tileDisplay(
                center.clone().add(0.0D, TableRenderConstants.CENTER_LAST_DISCARD_TILE_Y_OFFSET, 0.0D),
                0.0F,
                session.currentVariant(),
                snapshot.lastPublicDiscardTile(),
                DisplayEntities.TileRenderPose.STANDING
            )
                .scale(TableRenderConstants.CENTER_LAST_DISCARD_TILE_SCALE)
                .glowColor(TableRenderConstants.CENTER_LAST_DISCARD_TILE_GLOW)
                .billboard(Display.Billboard.VERTICAL)
                .spec());
        }
        return List.copyOf(specs);
    }

    private static Entity spawnCenterLastDiscardTile(
        TableRenderSubject session,
        Location center,
        MahjongTile tile
    ) {
        return DisplayEntities.tileDisplay(
            center.clone().add(0.0D, TableRenderConstants.CENTER_LAST_DISCARD_TILE_Y_OFFSET, 0.0D),
            0.0F,
            session.currentVariant(),
            tile,
            DisplayEntities.TileRenderPose.STANDING
        )
            .scale(TableRenderConstants.CENTER_LAST_DISCARD_TILE_SCALE)
            .glowColor(TableRenderConstants.CENTER_LAST_DISCARD_TILE_GLOW)
            .billboard(Display.Billboard.VERTICAL)
            .spawn(session.bukkitPlugin());
    }
}
