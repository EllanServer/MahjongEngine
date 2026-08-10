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
                        new SceneTransform(0, 0.375, 0, 0, 0, 0, 1)));

        ZoneTileCounts counts = ZoneTileCounts.from(projection.publicView().tiles());
        HashSet<TileInstanceId> revealedHands = null;
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
        }
        return revealedHands == null ? Set.of() : Set.copyOf(revealedHands);
    }
}
