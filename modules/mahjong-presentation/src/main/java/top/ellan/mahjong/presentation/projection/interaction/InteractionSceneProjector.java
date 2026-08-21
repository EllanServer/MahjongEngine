package top.ellan.mahjong.presentation.projection.interaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.label.ActionLabelPolicy;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.node.ActionLabelNodes;
import top.ellan.mahjong.presentation.node.InteractionNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.presentation.projection.support.SceneNodeIdentity;
import top.ellan.mahjong.presentation.projection.support.ViewerZoneCounts;
import top.ellan.mahjong.presentation.projection.support.ZoneTileCounts;
import top.ellan.mahjong.presentation.scene.SceneInteractionBinding;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Projects revision-bound hitboxes independently from visual scene construction. */
public final class InteractionSceneProjector {
    private final TableSceneAssets assets;
    private final boolean overheadEnabled;
    private final ActionRowProjector actionRows;

    public InteractionSceneProjector(TableSceneAssets assets, boolean overheadEnabled) {
        this(assets, overheadEnabled, ActionButtonMetrics.fallback());
    }

    public InteractionSceneProjector(
            TableSceneAssets assets,
            boolean overheadEnabled,
            ActionButtonMetrics buttonMetrics) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.overheadEnabled = overheadEnabled;
        actionRows = new ActionRowProjector(assets, buttonMetrics);
    }

    public void project(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout layout,
            ViewerZoneCounts viewerCounts) {
        Objects.requireNonNull(viewerCounts, "viewerCounts");
        for (PlayerId authorized : projection.authorizedActions().keySet()) {
            if (!projection.privateViews().containsKey(authorized)) {
                throw new IllegalArgumentException("authorized player has no private rule view");
            }
        }
        boolean addViewControl = overheadEnabled && projection.lifecycle().acceptsRuleActions();
        for (Map.Entry<PlayerId, PrivateRuleView> entry : projection.privateViews().entrySet()) {
            PlayerId player = entry.getKey();
            List<AuthorizedAction> actions =
                    projection.authorizedActions().getOrDefault(player, List.of());
            double firstRowWidth = addActions(
                    nodes,
                    bindings,
                    projection,
                    layout,
                    player,
                    entry.getValue(),
                    actions,
                    viewerCounts);
            if (addViewControl) {
                addViewControl(
                        nodes,
                        bindings,
                        projection,
                        player,
                        entry.getValue().seat(),
                        layout,
                        firstRowWidth);
            }
        }
    }

    private double addActions(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout layout,
            PlayerId player,
            PrivateRuleView privateView,
            List<AuthorizedAction> actions,
            ViewerZoneCounts viewerCounts) {
        ZoneTileCounts counts = viewerCounts.of(player, privateView);
        String playerKey = SceneNodeIdentity.compact(player);
        Map<TileInstanceId, RuleViewTile> handTiles = null;
        for (AuthorizedAction action : actions) {
            ActionPlacement placement = action.legalAction().actionPresentation().placement();
            if (placement == ActionPlacement.HAND_TILE) {
                if (handTiles == null) {
                    handTiles = handTileIndex(privateView);
                }
                addHandAction(
                        nodes,
                        bindings,
                        projection,
                        player,
                        playerKey,
                        counts,
                        handTiles,
                        action,
                        layout);
            }
        }
        double firstRowWidth = actionRows.add(
                nodes,
                bindings,
                projection,
                layout,
                player,
                playerKey,
                privateView.seat(),
                actions,
                ActionPlacement.ACTION_ROW);
        actionRows.add(
                nodes,
                bindings,
                projection,
                layout,
                player,
                playerKey,
                privateView.seat(),
                actions,
                ActionPlacement.SECONDARY_ROW);
        return firstRowWidth;
    }

    private void addViewControl(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            PlayerId player,
            SeatId seat,
            ResolvedTableLayout layout,
            double firstRowWidth) {
        String playerKey = SceneNodeIdentity.compact(player);
        SceneNodeId id = SceneNodeId.trusted("interaction/view/" + playerKey + "/river");
        SceneNodeId labelId = SceneNodeId.trusted("label/view/" + playerKey + "/river");
        InteractionHandle handle = SceneNodeIdentity.interaction(
                projection.tableId() + ":view:" + player + ":river");
        double width = actionRows.width(player, "action.view_river");
        double tangent = firstRowWidth == 0.0D
                ? -ActionLabelPolicy.EMPTY_ROW_PINNED_EDGE - width / 2.0D
                : -firstRowWidth / 2.0D - ActionLabelPolicy.PINNED_BUTTON_GAP - width / 2.0D;
        SceneTransform transform =
                layout.action(seat, ActionPlacement.ACTION_ROW, 0, tangent);
        nodes.put(
                id,
                new InteractionNode(
                        id,
                        SceneVisibility.publicToAll(),
                        handle,
                        assets.actionInteractionFurniture(width),
                        transform));
        nodes.put(
                labelId,
                ActionLabelNodes.create(
                        labelId,
                        SceneVisibility.privateTo(player),
                        "action.view_river",
                        transform,
                        false));
        bindings.add(SceneInteractionBinding.overhead(handle, player, projection.revision()));
    }

    private void addHandAction(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            PlayerId player,
            String playerKey,
            ZoneTileCounts counts,
            Map<TileInstanceId, RuleViewTile> handTiles,
            AuthorizedAction action,
            ResolvedTableLayout layout) {
        TileInstanceId target =
                action.legalAction().actionPresentation().targetTile().orElseThrow();
        RuleViewTile tile = handTiles.get(target);
        if (tile == null || tile.zone() != RuleViewZone.HAND) {
            throw new IllegalArgumentException("hand action target is absent from the private hand");
        }
        SceneNodeId id =
                SceneNodeId.trusted("interaction/hand/" + playerKey + '/' + target.value());
        InteractionHandle handle = SceneNodeIdentity.interaction(
                projection.tableId() + ":hand:" + player + ':' + target.value());
        InteractionNode node = new InteractionNode(
                id,
                SceneVisibility.publicToAll(),
                handle,
                assets.handInteractionFurniture(),
                layout.privateTile(tile, counts.count(tile)));
        if (nodes.putIfAbsent(id, node) != null) {
            throw new IllegalArgumentException(
                    "more than one direct action targets the same hand tile");
        }
        bindings.add(SceneInteractionBinding.handTile(handle, player, action.token(), target));
    }

    /** Single linear pass building the hand index, shared by every hand action of one player. */
    private static Map<TileInstanceId, RuleViewTile> handTileIndex(PrivateRuleView privateView) {
        Map<TileInstanceId, RuleViewTile> index =
                HashMap.newHashMap(privateView.tiles().size());
        for (RuleViewTile tile : privateView.tiles()) {
            index.put(tile.instanceId(), tile);
        }
        return index;
    }

}
