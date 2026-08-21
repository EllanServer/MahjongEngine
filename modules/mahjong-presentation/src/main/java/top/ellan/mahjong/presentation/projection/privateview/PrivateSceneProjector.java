package top.ellan.mahjong.presentation.projection.privateview;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.PrivateFurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.presentation.projection.support.SceneNodeIdentity;
import top.ellan.mahjong.presentation.projection.support.ViewerZoneCounts;
import top.ellan.mahjong.presentation.projection.support.ZoneTileCounts;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.TileInstanceId;

/** Projects secret hand faces, authorized HUD values and the optional private camera. */
public final class PrivateSceneProjector {
    private static final Set<String> PUBLIC_HUD_KEYS = Set.of(
            "round",
            "roundWind",
            "handNumber",
            "currentPlayer",
            "wall",
            "wallRemaining");
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
            Set<TileInstanceId> publiclyRevealedHands,
            ViewerZoneCounts viewerCounts) {
        Objects.requireNonNull(viewerCounts, "viewerCounts");
        Set<PlayerId> audience = Set.copyOf(projection.privateViews().keySet());
        if (!audience.isEmpty()) {
            // Phase and public attributes are identical for every seat, so they are projected once
            // for the shared audience rather than duplicated per viewer.
            projectSharedHud(nodes, projection, audience);
        }
        for (Map.Entry<PlayerId, PrivateRuleView> entry : projection.privateViews().entrySet()) {
            projectViewer(
                    nodes,
                    entry.getKey(),
                    entry.getValue(),
                    projection,
                    layout,
                    publiclyRevealedHands,
                    viewerCounts);
        }
    }

    private static void projectSharedHud(
            Map<SceneNodeId, SceneNode> nodes, TableProjection projection, Set<PlayerId> audience) {
        SceneVisibility visibility = SceneVisibility.privateTo(audience);
        SceneNodeId phaseId = SceneNodeId.trusted("hud/shared/phase");
        nodes.put(
                phaseId,
                new HudNode(phaseId, visibility, "phase", projection.publicView().phase()));
        SceneNodeId capacityId = SceneNodeId.trusted("hud/shared/wall-capacity");
        nodes.put(
                capacityId,
                new HudNode(
                        capacityId,
                        visibility,
                        "meta:wallCapacity",
                        Integer.toString(projection.publicView()
                                .tablePresentation()
                                .wall()
                                .tileCapacity())));
        projection.publicView().tablePresentation().currentSeat().ifPresent(currentSeat -> {
            SceneNodeId currentSeatId = SceneNodeId.trusted("hud/shared/current-seat");
            nodes.put(
                    currentSeatId,
                    new HudNode(
                            currentSeatId,
                            visibility,
                            "meta:currentSeat",
                            Integer.toString(currentSeat.value())));
        });
        addAttributes(
                nodes,
                visibility,
                "shared",
                "public",
                projection.publicView().attributes(),
                PUBLIC_HUD_KEYS);
    }

    private void projectViewer(
            Map<SceneNodeId, SceneNode> nodes,
            PlayerId viewer,
            PrivateRuleView privateView,
            TableProjection projection,
            ResolvedTableLayout layout,
            Set<TileInstanceId> publiclyRevealedHands,
            ViewerZoneCounts viewerCounts) {
        SceneVisibility visibility = SceneVisibility.privateTo(viewer);
        ZoneTileCounts counts = viewerCounts.of(viewer, privateView);
        String viewerKey = SceneNodeIdentity.compact(viewer);
        for (RuleViewTile tile : privateView.tiles()) {
            if (publiclyRevealedHands.contains(tile.instanceId())) {
                continue;
            }
            // One CE furniture now owns both the public back and this viewer's conditional face.
            // Remove the duplicate public-back furniture when the private representation exists.
            nodes.remove(SceneNodeIdentity.publicTile(tile.instanceId().value()));
            SceneNodeId id = SceneNodeIdentity.privateTile(viewerKey, tile.instanceId().value());
            nodes.put(
                    id,
                    new PrivateFurnitureNode(
                            id,
                            visibility,
                            tile.instanceId(),
                            tile.visualId(),
                            layout.privateTile(tile, counts.count(tile))));
        }

        if (overheadEnabled && projection.lifecycle().acceptsRuleActions()) {
            SceneNodeId cameraId = SceneNodeId.trusted("camera/" + viewerKey + "/river");
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
            String viewerKey,
            String namespace,
            Map<String, String> attributes,
            Set<String> includedKeys) {
        if (attributes.size() > 64) {
            throw new IllegalArgumentException("rule view exposes too many HUD attributes");
        }
        String[] keys = attributes.keySet().toArray(String[]::new);
        Arrays.sort(keys);
        for (String key : keys) {
            if (!includedKeys.contains(key)) {
                continue;
            }
            SceneNodeId id = SceneNodeIdentity.hud(viewerKey, namespace, key);
            String contentKey = namespace + ':' + key;
            if (nodes.putIfAbsent(
                            id,
                            new HudNode(
                                    id,
                                    visibility,
                                    contentKey,
                                    attributes.get(key)))
                    != null) {
                throw new IllegalStateException("HUD attribute id collision");
            }
        }
    }
}
