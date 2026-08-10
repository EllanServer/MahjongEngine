package top.ellan.mahjong.presentation.projection.interaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.application.interaction.InteractionHandle;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
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
    private static final double ACTION_LABEL_RAISE = 0.12D;

    private final TableSceneAssets assets;
    private final boolean overheadEnabled;

    public InteractionSceneProjector(TableSceneAssets assets, boolean overheadEnabled) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.overheadEnabled = overheadEnabled;
    }

    public void project(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout layout,
            ViewerZoneCounts viewerCounts) {
        Objects.requireNonNull(viewerCounts, "viewerCounts");
        for (Map.Entry<PlayerId, List<AuthorizedAction>> entry
                : projection.authorizedActions().entrySet()) {
            PlayerId player = entry.getKey();
            PrivateRuleView privateView = projection.privateViews().get(player);
            if (privateView == null) {
                throw new IllegalArgumentException("authorized player has no private rule view");
            }
            addActions(
                    nodes,
                    bindings,
                    projection,
                    layout,
                    player,
                    privateView,
                    entry.getValue(),
                    viewerCounts);
        }
        if (overheadEnabled && projection.lifecycle().acceptsRuleActions()) {
            for (Map.Entry<PlayerId, PrivateRuleView> entry : projection.privateViews().entrySet()) {
                addViewControl(
                        nodes,
                        bindings,
                        projection,
                        entry.getKey(),
                        entry.getValue().seat(),
                        layout);
            }
        }
    }

    private void addActions(
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
        int primaryIndex = 0;
        int secondaryIndex = 0;
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
                continue;
            }
            int index = placement == ActionPlacement.ACTION_ROW
                    ? primaryIndex++
                    : secondaryIndex++;
            addRowAction(
                    nodes,
                    bindings,
                    projection,
                    player,
                    playerKey,
                    privateView.seat(),
                    action,
                    placement,
                    index,
                    layout);
        }
    }

    private void addViewControl(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            PlayerId player,
            SeatId seat,
            ResolvedTableLayout layout) {
        String playerKey = SceneNodeIdentity.compact(player);
        SceneNodeId id = SceneNodeId.trusted("interaction/view/" + playerKey + "/river");
        SceneNodeId labelId = SceneNodeId.trusted("label/view/" + playerKey + "/river");
        InteractionHandle handle = SceneNodeIdentity.interaction(
                projection.tableId() + ":view:" + player + ":river");
        SceneTransform transform = layout.viewControl(seat);
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
        Map<TileInstanceId, RuleViewTile> index = new HashMap<>();
        for (RuleViewTile tile : privateView.tiles()) {
            index.put(tile.instanceId(), tile);
        }
        return index;
    }

    private void addRowAction(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            PlayerId player,
            String playerKey,
            SeatId seat,
            AuthorizedAction action,
            ActionPlacement placement,
            int index,
            ResolvedTableLayout layout) {
        // The action key originates from the rule pack, so the id must go through the validating
        // constructor; only the per-player compact() is hoisted out of the loop.
        String actionKey = action.legalAction().key();
        SceneNodeId id =
                new SceneNodeId("interaction/action/" + playerKey + '/' + actionKey);
        SceneNodeId labelId = new SceneNodeId("label/action/" + playerKey + '/' + actionKey);
        InteractionHandle handle = SceneNodeIdentity.interaction(
                projection.tableId() + ":action:" + player + ':' + actionKey);
        SceneTransform transform = layout.action(seat, placement, index);
        if (nodes.putIfAbsent(
                        id,
                        new InteractionNode(
                                id,
                                SceneVisibility.publicToAll(),
                                handle,
                                assets.actionInteractionFurniture(),
                                transform))
                != null) {
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
}
