package top.ellan.mahjong.presentation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import top.ellan.mahjong.application.InteractionHandle;
import top.ellan.mahjong.application.TableProjection;
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

    private final TableLayout layout;
    private final TableSceneAssets assets;

    public DefaultTableSceneMapper(TableLayout layout, TableSceneAssets assets) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.assets = Objects.requireNonNull(assets, "assets");
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

        List<Map.Entry<PlayerId, PrivateRuleView>> privateEntries =
                projection.privateViews().entrySet().stream()
                        .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                        .toList();
        for (Map.Entry<PlayerId, PrivateRuleView> entry : privateEntries) {
            addPrivateView(
                    nodes,
                    projection,
                    entry.getKey(),
                    entry.getValue(),
                    resolvedLayout);
        }
        addInteractions(nodes, bindings, projection, resolvedLayout);
        return new SceneGraph(projection.tableId(), projection.revision(), nodes, bindings);
    }

    private void addPrivateView(
            Map<SceneNodeId, SceneNode> nodes,
            TableProjection projection,
            PlayerId viewer,
            PrivateRuleView privateView,
            ResolvedTableLayout resolvedLayout) {
        SceneVisibility visibility = SceneVisibility.privateTo(viewer);
        ZoneCounts counts = ZoneCounts.from(privateView.tiles());
        String viewerKey = compact(viewer);
        for (RuleViewTile tile : privateView.tiles()) {
            SceneNodeId id = new SceneNodeId(
                    "tile/private/" + viewerKey + '/' + tile.instanceId().value());
            nodes.put(
                    id,
                    new PrivateItemNode(
                            id,
                            visibility,
                            tile.visualId(),
                            resolvedLayout.tile(tile, counts.count(tile))));
        }

        SceneNodeId phaseId = new SceneNodeId("hud/" + viewerKey + "/phase");
        nodes.put(
                phaseId,
                new HudNode(
                        phaseId,
                        visibility,
                        "phase",
                        projection.publicView().phase()));
        String actionLabels = projection.authorizedActions().getOrDefault(viewer, List.of()).stream()
                .filter(action -> action.legalAction().actionPresentation().placement()
                        != ActionPlacement.HAND_TILE)
                .map(action -> action.legalAction().actionPresentation().labelKey())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        SceneNodeId actionsId = new SceneNodeId("hud/" + viewerKey + "/actions");
        nodes.put(actionsId, new HudNode(actionsId, visibility, "actions", actionLabels));
    }

    private void addInteractions(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout resolvedLayout) {
        List<Map.Entry<PlayerId, List<AuthorizedAction>>> entries =
                projection.authorizedActions().entrySet().stream()
                        .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                        .toList();
        for (Map.Entry<PlayerId, List<AuthorizedAction>> entry : entries) {
            PlayerId player = entry.getKey();
            PrivateRuleView privateView = projection.privateViews().get(player);
            if (privateView == null) {
                throw new IllegalArgumentException("authorized player has no private rule view");
            }
            List<AuthorizedAction> actions = entry.getValue().stream()
                    .sorted(Comparator.comparing(action -> action.legalAction().key()))
                    .toList();
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
                resolvedLayout.tile(tile, counts.count(tile)));
        if (nodes.putIfAbsent(id, node) != null) {
            throw new IllegalArgumentException("more than one direct action targets the same hand tile");
        }
        bindings.add(new SceneInteractionBinding(handle, player, action.token()));
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
        InteractionHandle handle = handle(
                projection.tableId() + ":action:" + player + ':' + actionKey);
        InteractionNode node = new InteractionNode(
                id,
                SceneVisibility.publicToAll(),
                handle,
                assets.actionInteractionFurniture(),
                resolvedLayout.action(seat, placement, index));
        if (nodes.putIfAbsent(id, node) != null) {
            throw new IllegalArgumentException("duplicate action interaction node");
        }
        bindings.add(new SceneInteractionBinding(handle, player, action.token()));
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
