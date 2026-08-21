package top.ellan.mahjong.presentation.projection.interaction;

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
import top.ellan.mahjong.presentation.scene.SceneInteractionBinding;
import top.ellan.mahjong.spi.ActionPlacement;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Builds localized, dynamically centered rows without doing work on a server tick. */
final class ActionRowProjector {
    private final TableSceneAssets assets;
    private final ActionButtonMetrics metrics;

    ActionRowProjector(TableSceneAssets assets, ActionButtonMetrics metrics) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    double add(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout layout,
            PlayerId player,
            String playerKey,
            SeatId seat,
            List<AuthorizedAction> actions,
            ActionPlacement placement) {
        AuthorizedAction[] rowActions = new AuthorizedAction[ActionLabelPolicy.BUTTONS_PER_ROW];
        int[] ordinals = new int[rowActions.length];
        double[] widths = new double[rowActions.length];
        int count = 0;
        int row = 0;
        double firstRowWidth = 0.0D;
        for (int ordinal = 0; ordinal < actions.size(); ordinal++) {
            AuthorizedAction action = actions.get(ordinal);
            if (action.legalAction().actionPresentation().placement() != placement) {
                continue;
            }
            rowActions[count] = action;
            ordinals[count] = ordinal;
            widths[count] = width(
                    player, action.legalAction().actionPresentation().labelKey());
            count++;
            if (count == rowActions.length) {
                double rowWidth = addRow(
                        nodes, bindings, projection, layout, player, playerKey, seat,
                        placement, row, rowActions, ordinals, widths, count);
                if (row++ == 0) {
                    firstRowWidth = rowWidth;
                }
                count = 0;
            }
        }
        if (count > 0) {
            double rowWidth = addRow(
                    nodes, bindings, projection, layout, player, playerKey, seat,
                    placement, row, rowActions, ordinals, widths, count);
            if (row == 0) {
                firstRowWidth = rowWidth;
            }
        }
        return firstRowWidth;
    }

    double width(PlayerId player, String labelKey) {
        double requested = metrics.width(player, labelKey);
        return ActionLabelPolicy.variantWidth(ActionLabelPolicy.variantIndex(requested));
    }

    private double addRow(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout layout,
            PlayerId player,
            String playerKey,
            SeatId seat,
            ActionPlacement placement,
            int row,
            AuthorizedAction[] actions,
            int[] ordinals,
            double[] widths,
            int count) {
        double rowWidth = Math.max(0, count - 1) * ActionLabelPolicy.BUTTON_GAP;
        for (int index = 0; index < count; index++) {
            rowWidth += widths[index];
        }
        double cursor = -rowWidth / 2.0D;
        for (int index = 0; index < count; index++) {
            double tangent = cursor + widths[index] / 2.0D;
            cursor += widths[index] + ActionLabelPolicy.BUTTON_GAP;
            addAction(
                    nodes, bindings, projection, layout, player, playerKey, seat,
                    placement, row, tangent, widths[index], ordinals[index], actions[index]);
        }
        return rowWidth;
    }

    private void addAction(
            Map<SceneNodeId, SceneNode> nodes,
            List<SceneInteractionBinding> bindings,
            TableProjection projection,
            ResolvedTableLayout layout,
            PlayerId player,
            String playerKey,
            SeatId seat,
            ActionPlacement placement,
            int row,
            double tangent,
            double width,
            int ordinal,
            AuthorizedAction action) {
        String identityKey = action.legalAction().key() + '-' + ordinal;
        SceneNodeId id =
                new SceneNodeId("interaction/action/" + playerKey + '/' + identityKey);
        SceneNodeId labelId =
                new SceneNodeId("label/action/" + playerKey + '/' + identityKey);
        InteractionHandle handle = SceneNodeIdentity.interaction(
                projection.tableId() + ":action:" + player + ':' + identityKey);
        SceneTransform transform = layout.action(seat, placement, row, tangent);
        InteractionNode interaction = new InteractionNode(
                id,
                SceneVisibility.publicToAll(),
                handle,
                assets.actionInteractionFurniture(width),
                transform);
        if (nodes.putIfAbsent(id, interaction) != null) {
            throw new IllegalArgumentException("duplicate action interaction node");
        }
        SceneNode label = ActionLabelNodes.create(
                labelId,
                SceneVisibility.privateTo(player),
                action.legalAction().actionPresentation().labelKey(),
                transform,
                action.legalAction().actionPresentation().emphasized());
        if (nodes.putIfAbsent(labelId, label) != null) {
            throw new IllegalArgumentException("duplicate action label node");
        }
        bindings.add(new SceneInteractionBinding(handle, player, action.token()));
    }

}
