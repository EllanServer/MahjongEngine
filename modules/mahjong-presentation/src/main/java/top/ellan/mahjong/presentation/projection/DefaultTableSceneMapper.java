package top.ellan.mahjong.presentation.projection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.asset.TileAssetName;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.layout.TableLayout;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.FurnitureNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.presentation.scene.SceneGraph;
import top.ellan.mahjong.presentation.scene.SceneInteractionBinding;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.RuleTablePresentation;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Maps immutable rule views to stable CraftEngine and client-private scene nodes. */
public final class DefaultTableSceneMapper implements TableSceneMapper {
    private static final int OWNERLESS = 4;
    private static final int OWNER_BUCKETS = 5;
    private static final double ACTION_LABEL_RAISE = 0.12D;

    private final TableLayout layout;
    private final TableSceneAssets assets;
    private final double overheadHeight;
    private final boolean overheadEnabled;

    public DefaultTableSceneMapper(TableLayout layout, TableSceneAssets assets) {
        this(layout, assets, 4.5D, true);
    }

    public DefaultTableSceneMapper(
            TableLayout layout, TableSceneAssets assets, double overheadHeight) {
        this(layout, assets, overheadHeight, true);
    }

    public DefaultTableSceneMapper(
            TableLayout layout,
            TableSceneAssets assets,
            double overheadHeight,
            boolean overheadEnabled) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.assets = Objects.requireNonNull(assets, "assets");
        if (!Double.isFinite(overheadHeight) || overheadHeight <= 0.0D) {
            throw new IllegalArgumentException("overheadHeight must be finite and positive");
        }
        this.overheadHeight = overheadHeight;
        this.overheadEnabled = overheadEnabled;
    }

    @Override
    public SceneGraph map(TableProjection projection) {
        Objects.requireNonNull(projection, "projection");
        Map<SceneNodeId, SceneNode> nodes = new LinkedHashMap<>();
        List<SceneInteractionBinding> bindings = new ArrayList<>();
        addTable(nodes);

        RuleTablePresentation table = projection.publicView().tablePresentation();
        ResolvedTableLayout resolvedLayout = layout.resolve(table);
        ZoneCounts publicCounts = ZoneCounts.from(projection.publicView().tiles());
        for (RuleViewTile tile : projection.publicView().tiles()) {
            SceneNodeId id = new SceneNodeId("tile/public/" + tile.instanceId().value());
            nodes.put(
                    id,
                    new FurnitureNode(
                            id,
                            SceneVisibility.publicToAll(),
                            furnitureAsset(tile),
                            resolvedLayout.tile(tile, publicCounts.count(tile))));
        }

        Set<TileInstanceId> publiclyRevealedHands = publiclyRevealedHands(projection);
        for (Map.Entry<PlayerId, PrivateRuleView> entry : projection.privateViews().entrySet()) {
            addPrivateView(
                    nodes,
                    projection,
                    entry.getKey(),
                    entry.getValue(),
                    resolvedLayout,
                    publiclyRevealedHands);
        }
        addInteractions(nodes, bindings, projection, resolvedLayout);
        return new SceneGraph(projection.tableId(), projection.revision(), nodes, bindings);
    }

    private void addPrivateView(
            Map<SceneNodeId, SceneNode> nodes,
            TableProjection projection,
            PlayerId viewer,
            PrivateRuleView privateView,
            ResolvedTableLayout resolvedLayout,
            Set<TileInstanceId> publiclyRevealedHands) {
        SceneVisibility visibility = SceneVisibility.privateTo(viewer);
        ZoneCounts counts = ZoneCounts.from(privateView.tiles());
        String viewerKey = compact(viewer);
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
                            resolvedLayout.privateTile(tile, counts.count(tile))));
        }

        SceneNodeId phaseId = new SceneNodeId("hud/" + viewerKey + "/phase");
        nodes.put(
                phaseId,
                new HudNode(
                        phaseId,
                        visibility,
                        "phase",
                        projection.publicView().phase()));
        addHudAttributes(
                nodes,
                visibility,
                viewerKey,
                "public",
                projection.publicView().attributes());
        addHudAttributes(
                nodes,
                visibility,
                viewerKey,
                "private",
                privateView.attributes());
        if (overheadEnabled && projection.lifecycle().acceptsRuleActions()) {
            SceneNodeId cameraId = new SceneNodeId("camera/" + viewerKey + "/river");
            nodes.put(
                    cameraId,
                    new CameraNode(
                            cameraId,
                            visibility,
                            resolvedLayout.overheadCamera(privateView.seat(), overheadHeight),
                            false));
        }
    }

    private void addInteractions(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout resolvedLayout) {
        for (Map.Entry<PlayerId, List<AuthorizedAction>> entry
                : projection.authorizedActions().entrySet()) {
            PlayerId player = entry.getKey();
            PrivateRuleView privateView = projection.privateViews().get(player);
            if (privateView == null) {
                throw new IllegalArgumentException("authorized player has no private rule view");
            }
            List<AuthorizedAction> actions = entry.getValue();
            ZoneCounts privateCounts = ZoneCounts.from(privateView.tiles());
            int primaryIndex = 0;
            int secondaryIndex = 0;
            for (AuthorizedAction action : actions) {
                ActionPlacement placement = action.legalAction().actionPresentation().placement();
                if (placement == ActionPlacement.HAND_TILE) {
                    addHandInteraction(
                            nodes,
                            bindings,
                            projection,
                            player,
                            privateView,
                            privateCounts,
                            action,
                            resolvedLayout);
                    continue;
                }
                int index = placement == ActionPlacement.ACTION_ROW
                        ? primaryIndex++
                        : secondaryIndex++;
                addActionInteraction(
                        nodes,
                        bindings,
                        projection,
                        player,
                        privateView.seat(),
                        action,
                        placement,
                        index,
                        resolvedLayout);
            }
        }
        if (overheadEnabled && projection.lifecycle().acceptsRuleActions()) {
            for (Map.Entry<PlayerId, PrivateRuleView> entry : projection.privateViews().entrySet()) {
                addViewInteraction(
                        nodes,
                        bindings,
                        projection,
                        entry.getKey(),
                        entry.getValue().seat(),
                        resolvedLayout);
            }
        }
    }

    private static void addHudAttributes(
            Map<SceneNodeId, SceneNode> nodes,
            SceneVisibility visibility,
            String viewerKey,
            String namespace,
            Map<String, String> attributes) {
        if (attributes.size() > 64) {
            throw new IllegalArgumentException("rule view exposes too many HUD attributes");
        }
        attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            String material = namespace + ':' + entry.getKey();
                            String stable = UUID.nameUUIDFromBytes(
                                            material.getBytes(StandardCharsets.UTF_8))
                                    .toString()
                                    .replace("-", "");
                            SceneNodeId id = new SceneNodeId(
                                    "hud/" + viewerKey + '/' + namespace + '/' + stable);
                            if (nodes.putIfAbsent(
                                            id,
                                            new HudNode(
                                                    id,
                                                    visibility,
                                                    material,
                                                    entry.getValue()))
                                    != null) {
                                throw new IllegalStateException("HUD attribute id collision");
                            }
                        });
    }

    private void addViewInteraction(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            PlayerId player,
            SeatId seat,
            ResolvedTableLayout resolvedLayout) {
        String playerKey = compact(player);
        SceneNodeId id = new SceneNodeId("interaction/view/" + playerKey + "/river");
        SceneNodeId labelId = new SceneNodeId("label/view/" + playerKey + "/river");
        InteractionHandle handle = handle(
                projection.tableId() + ":view:" + player + ":river");
        SceneTransform transform = resolvedLayout.viewControl(seat);
        nodes.put(
                id,
                new InteractionNode(
                        id,
                        SceneVisibility.publicToAll(),
                        handle,
                        assets.actionInteractionFurniture(),
                        transform));
        nodes.put(
                labelId,
                new ActionLabelNode(
                        labelId,
                        SceneVisibility.privateTo(player),
                        "action.view_river",
                        labelTransform(transform),
                        false));
        bindings.add(SceneInteractionBinding.overhead(
                handle, player, projection.revision()));
    }

    private void addHandInteraction(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            PlayerId player,
            PrivateRuleView privateView,
            ZoneCounts counts,
            AuthorizedAction action,
            ResolvedTableLayout resolvedLayout) {
        TileInstanceId target = action.legalAction().actionPresentation().targetTile().orElseThrow();
        RuleViewTile tile = null;
        for (RuleViewTile candidate : privateView.tiles()) {
            if (candidate.instanceId().equals(target)) {
                tile = candidate;
                break;
            }
        }
        if (tile == null || tile.zone() != RuleViewZone.HAND) {
            throw new IllegalArgumentException("hand action target is absent from the private hand");
        }
        String playerKey = compact(player);
        SceneNodeId id = new SceneNodeId(
                "interaction/hand/" + playerKey + '/' + target.value());
        InteractionHandle handle = handle(
                projection.tableId() + ":hand:" + player + ':' + target.value());
        InteractionNode node = new InteractionNode(
                id,
                SceneVisibility.publicToAll(),
                handle,
                assets.handInteractionFurniture(),
                resolvedLayout.privateTile(tile, counts.count(tile)));
        if (nodes.putIfAbsent(id, node) != null) {
            throw new IllegalArgumentException("more than one direct action targets the same hand tile");
        }
        bindings.add(SceneInteractionBinding.handTile(handle, player, action.token(), target));
    }

    private void addActionInteraction(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            PlayerId player,
            SeatId seat,
            AuthorizedAction action,
            ActionPlacement placement,
            int index,
            ResolvedTableLayout resolvedLayout) {
        String playerKey = compact(player);
        String actionKey = action.legalAction().key();
        SceneNodeId id = new SceneNodeId(
                "interaction/action/" + playerKey + '/' + actionKey);
        SceneNodeId labelId = new SceneNodeId(
                "label/action/" + playerKey + '/' + actionKey);
        InteractionHandle handle = handle(
                projection.tableId() + ":action:" + player + ':' + actionKey);
        SceneTransform transform = resolvedLayout.action(seat, placement, index);
        InteractionNode node = new InteractionNode(
                id,
                SceneVisibility.publicToAll(),
                handle,
                assets.actionInteractionFurniture(),
                transform);
        if (nodes.putIfAbsent(id, node) != null) {
            throw new IllegalArgumentException("duplicate action interaction node");
        }
        if (nodes.putIfAbsent(
                        labelId,
                        new ActionLabelNode(
                                labelId,
                                SceneVisibility.privateTo(player),
                                action.legalAction().actionPresentation().labelKey(),
                                labelTransform(transform),
                                action.legalAction().actionPresentation().emphasized()))
                != null) {
            throw new IllegalArgumentException("duplicate action label node");
        }
        bindings.add(new SceneInteractionBinding(handle, player, action.token()));
    }

    private static SceneTransform labelTransform(SceneTransform base) {
        return new SceneTransform(
                base.x(),
                base.y() + ACTION_LABEL_RAISE,
                base.z(),
                base.yawDegrees(),
                base.pitchDegrees(),
                base.rollDegrees(),
                base.scale());
    }

    private void addTable(Map<SceneNodeId, SceneNode> nodes) {
        SceneNodeId id = new SceneNodeId("furniture/table");
        nodes.put(
                id,
                new FurnitureNode(
                        id,
                        SceneVisibility.publicToAll(),
                        assets.tableFurniture(),
                        new SceneTransform(0, 0, 0, 0, 0, 0, 1)));
    }

    private String furnitureAsset(RuleViewTile tile) {
        if (tile.zone() == RuleViewZone.POINT_STICK) {
            return pointStickFurnitureAsset(tile.visualId().value());
        }
        if (!tile.faceUp()) {
            return tile.zone() == RuleViewZone.HAND
                    ? assets.standingBackFurniture()
                    : assets.flatBackFurniture();
        }
        String tileName = TileAssetName.from(tile.visualId());
        String pose = tile.zone() == RuleViewZone.HAND
                ? "tile_standing"
                : "tile_flat_face_up";
        return "mahjongpaper:" + pose + '_' + tileName;
    }

    private static String pointStickFurnitureAsset(String visualId) {
        int separator = visualId.indexOf("stick/");
        String denomination = separator >= 0
                ? visualId.substring(separator + "stick/".length())
                : visualId;
        if (!denomination.matches("p(?:100|1000|5000|10000)")) {
            throw new IllegalArgumentException("Unsupported point-stick visual id: " + visualId);
        }
        return "mahjongpaper:stick_" + denomination;
    }

    private static InteractionHandle handle(String material) {
        return new InteractionHandle(
                UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static String compact(PlayerId player) {
        return player.toString().replace("-", "");
    }

    private static Set<TileInstanceId> publiclyRevealedHands(TableProjection projection) {
        HashSet<TileInstanceId> revealed = null;
        for (RuleViewTile tile : projection.publicView().tiles()) {
            if (tile.zone() == RuleViewZone.HAND && tile.faceUp()) {
                if (revealed == null) {
                    revealed = new HashSet<>();
                }
                revealed.add(tile.instanceId());
            }
        }
        return revealed == null ? Set.of() : revealed;
    }

    private static final class ZoneCounts {
        private final int[][] counts =
                new int[RuleViewZone.values().length][OWNER_BUCKETS];

        static ZoneCounts from(List<RuleViewTile> tiles) {
            ZoneCounts result = new ZoneCounts();
            for (RuleViewTile tile : tiles) {
                result.counts[tile.zone().ordinal()][ownerBucket(tile)]++;
            }
            return result;
        }

        int count(RuleViewTile tile) {
            return counts[tile.zone().ordinal()][ownerBucket(tile)];
        }

        private static int ownerBucket(RuleViewTile tile) {
            if (tile.zone() == RuleViewZone.WIN_CLAIM
                    || tile.zone() == RuleViewZone.AUXILIARY) {
                return OWNERLESS;
            }
            return tile.owner().map(SeatId::value).orElse(OWNERLESS);
        }
    }
}
