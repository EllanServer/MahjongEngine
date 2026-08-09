package top.ellan.mahjong.presentation.projection.privateview;

import java.util.Map;
import java.util.Set;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.presentation.projection.support.SceneNodeIdentity;
import top.ellan.mahjong.presentation.projection.support.ZoneTileCounts;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.TileInstanceId;

/** Projects secret hand faces, authorized HUD values and the optional private camera. */
public final class PrivateSceneProjector {
    private final double overheadHeight;
    private final boolean overheadEnabled;

    public PrivateSceneProjector(double overheadHeight, boolean overheadEnabled) {
        if (!Double.isFinite(overheadHeight) || overheadHeight <= 0.0D) {
            throw new IllegalArgumentException("overheadHeight must be finite and positive");
        }
        this.overheadHeight = overheadHeight;
        this.overheadEnabled = overheadEnabled;
    }

    public void project(
            Map<SceneNodeId, SceneNode> nodes,
            TableProjection projection,
            ResolvedTableLayout layout,
            Set<TileInstanceId> publiclyRevealedHands) {
        for (Map.Entry<PlayerId, PrivateRuleView> entry : projection.privateViews().entrySet()) {
            projectViewer(
                    nodes,
                    projection,
                    entry.getKey(),
                    entry.getValue(),
                    layout,
                    publiclyRevealedHands);
        }
    }

    private void projectViewer(
            Map<SceneNodeId, SceneNode> nodes,
            TableProjection projection,
            PlayerId viewer,
            PrivateRuleView privateView,
            ResolvedTableLayout layout,
            Set<TileInstanceId> publiclyRevealedHands) {
        SceneVisibility visibility = SceneVisibility.privateTo(viewer);
        ZoneTileCounts counts = ZoneTileCounts.from(privateView.tiles());
        String viewerKey = SceneNodeIdentity.compact(viewer);
        for (RuleViewTile tile : privateView.tiles()) {
            if (publiclyRevealedHands.contains(tile.instanceId())) {
                continue;
            }
            SceneNodeId id = new SceneNodeId(
                    "tile/private/" + viewerKey + '/' + tile.instanceId().value());
            nodes.put(
                    id,
                    new PrivateItemNode(
                            id,
                            visibility,
                            tile.instanceId(),
                            tile.visualId(),
                            layout.privateTile(tile, counts.count(tile))));
        }

        SceneNodeId phaseId = new SceneNodeId("hud/" + viewerKey + "/phase");
        nodes.put(
                phaseId,
                new HudNode(phaseId, visibility, "phase", projection.publicView().phase()));
        addAttributes(nodes, visibility, viewer, "public", projection.publicView().attributes());
        addAttributes(nodes, visibility, viewer, "private", privateView.attributes());
        if (overheadEnabled && projection.lifecycle().acceptsRuleActions()) {
            SceneNodeId cameraId = new SceneNodeId("camera/" + viewerKey + "/river");
            nodes.put(
                    cameraId,
                    new CameraNode(
                            cameraId,
                            visibility,
                            layout.overheadCamera(privateView.seat(), overheadHeight),
                            false));
        }
    }

    private static void addAttributes(
            Map<SceneNodeId, SceneNode> nodes,
            SceneVisibility visibility,
            PlayerId viewer,
            String namespace,
            Map<String, String> attributes) {
        if (attributes.size() > 64) {
            throw new IllegalArgumentException("rule view exposes too many HUD attributes");
        }
        attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    SceneNodeId id = SceneNodeIdentity.hud(viewer, namespace, entry.getKey());
                    String contentKey = namespace + ':' + entry.getKey();
                    if (nodes.putIfAbsent(
                                    id,
                                    new HudNode(
                                            id,
                                            visibility,
                                            contentKey,
                                            entry.getValue()))
                            != null) {
                        throw new IllegalStateException("HUD attribute id collision");
                    }
                });
    }
}
