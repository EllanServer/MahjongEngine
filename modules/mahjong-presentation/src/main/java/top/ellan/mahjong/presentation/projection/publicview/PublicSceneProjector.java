package top.ellan.mahjong.presentation.projection.publicview;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.presentation.projection.asset.RuleTileFurnitureResolver;
import top.ellan.mahjong.presentation.projection.support.SceneNodeIdentity;
import top.ellan.mahjong.presentation.projection.support.ZoneTileCounts;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.TileInstanceId;

/** Projects only information that every nearby client is allowed to receive. */
public final class PublicSceneProjector {
    private static final SceneTransform TABLE_TRANSFORM =
            new SceneTransform(0, 0.895D, 0, 0, 0, 0, 1);
    /** Chair facing uses Bukkit yaw: every forward vector points from its seat to table centre. */
    private static final SceneTransform[] SEAT_TRANSFORMS = {
        new SceneTransform(2.125D, 0.9D, 0, 90, 0, 0, 1),
        new SceneTransform(0, 0.9D, 2.125D, 180, 0, 0, 1),
        new SceneTransform(-2.125D, 0.9D, 0, 270, 0, 0, 1),
        new SceneTransform(0, 0.9D, -2.125D, 0, 0, 0, 1)
    };
    private final TableSceneAssets assets;
    private final RuleTileFurnitureResolver furniture;

    public PublicSceneProjector(
            TableSceneAssets assets, RuleTileFurnitureResolver furniture) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.furniture = Objects.requireNonNull(furniture, "furniture");
    }

    public Set<TileInstanceId> project(
            Map<SceneNodeId, SceneNode> nodes,
            TableProjection projection,
            ResolvedTableLayout layout) {
        SceneNodeId tableId = SceneNodeId.trusted("furniture/table");
        nodes.put(
                tableId,
                new FurnitureNode(
                        tableId,
                        SceneVisibility.publicToAll(),
                        assets.tableFurniture(),
                        TABLE_TRANSFORM));
        for (int seat = 0; seat < SEAT_TRANSFORMS.length; seat++) {
            SceneNodeId seatId = SceneNodeId.trusted("furniture/seat/" + seat);
            nodes.put(
                    seatId,
                    new FurnitureNode(
                            seatId,
                            SceneVisibility.publicToAll(),
                            assets.seatFurniture(),
                            SEAT_TRANSFORMS[seat]));
        }

        ZoneTileCounts counts = ZoneTileCounts.from(projection.publicView().tiles());
        HashSet<TileInstanceId> revealedHands = null;
        TileInstanceId highlighted =
                projection.publicView().tablePresentation().lastDiscard().orElse(null);
        RuleViewTile highlightedTile = null;
        for (RuleViewTile tile : projection.publicView().tiles()) {
            SceneNodeId id = SceneNodeIdentity.publicTile(tile.instanceId().value());
            nodes.put(
                    id,
                    new FurnitureNode(
                            id,
                            SceneVisibility.publicToAll(),
                            furniture.resolve(tile),
                            layout.tile(tile, counts.count(tile))));
            if (tile.zone() == RuleViewZone.HAND && tile.faceUp()) {
                if (revealedHands == null) {
                    revealedHands = new HashSet<>();
                }
                revealedHands.add(tile.instanceId());
            }
            if (highlighted != null && highlighted.equals(tile.instanceId())) {
                highlightedTile = tile;
            }
        }
        projectLastDiscardHighlight(nodes, highlightedTile, layout);
        return revealedHands == null ? Set.of() : Set.copyOf(revealedHands);
    }

    /**
     * Restores the 1.5.0 centre highlight: an enlarged copy of the newest discard floats above the
     * table so every seat can read the tile a call would be made on. Omitting the node when there is
     * no pending discard lets the scene differ retire it.
     */
    private void projectLastDiscardHighlight(
            Map<SceneNodeId, SceneNode> nodes, RuleViewTile tile, ResolvedTableLayout layout) {
        if (tile == null || !tile.faceUp()) {
            return;
        }
        SceneNodeId id = SceneNodeId.trusted("furniture/last-discard");
        nodes.put(
                id,
                new FurnitureNode(
                        id,
                        SceneVisibility.publicToAll(),
                        furniture.resolve(tile),
                        layout.lastDiscardHighlight()));
    }
}
