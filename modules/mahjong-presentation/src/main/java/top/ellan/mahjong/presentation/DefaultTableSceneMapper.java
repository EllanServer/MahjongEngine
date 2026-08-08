package top.ellan.mahjong.presentation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import top.ellan.mahjong.application.InteractionHandle;
import top.ellan.mahjong.application.TableProjection;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.RuleViewTile;

/** Default conversion that keeps every public tile in CE furniture and every secret face client-only. */
public final class DefaultTableSceneMapper implements TableSceneMapper {
    private final TableLayout layout;
    private final String tileBackAsset;

    public DefaultTableSceneMapper(TableLayout layout, String tileBackAsset) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.tileBackAsset = Objects.requireNonNull(tileBackAsset, "tileBackAsset");
        // Reuse FurnitureNode's strict asset validation at construction time.
        new FurnitureNode(
                new SceneNodeId("validation/tile-back"),
                SceneVisibility.publicToAll(),
                tileBackAsset,
                new SceneTransform(0, 0, 0, 0, 0, 0, 1),
                32);
    }

    @Override
    public SceneGraph map(TableProjection projection) {
        Map<SceneNodeId, SceneNode> nodes = new LinkedHashMap<>();
        for (RuleViewTile tile : projection.publicView().tiles()) {
            SceneNodeId id = new SceneNodeId("tile/public/" + tile.instanceId().value());
            String asset = tile.faceUp() ? tileAsset(tile) : tileBackAsset;
            nodes.put(
                    id,
                    new FurnitureNode(
                            id,
                            SceneVisibility.publicToAll(),
                            asset,
                            layout.tile(tile.zone(), tile.owner(), tile.index()),
                            32));
        }

        for (Map.Entry<PlayerId, PrivateRuleView> entry : projection.privateViews().entrySet()) {
            PlayerId viewer = entry.getKey();
            SceneVisibility visibility = SceneVisibility.privateTo(viewer);
            for (RuleViewTile tile : entry.getValue().tiles()) {
                SceneNodeId id =
                        new SceneNodeId(
                                "tile/private/"
                                        + viewer.toString().replace("-", "")
                                        + '/'
                                        + tile.instanceId().value());
                nodes.put(
                        id,
                        new PrivateItemNode(
                                id,
                                visibility,
                                tile.visualId(),
                                layout.tile(tile.zone(), tile.owner(), tile.index())));
            }
            SceneNodeId phaseId =
                    new SceneNodeId("hud/" + viewer.toString().replace("-", "") + "/phase");
            nodes.put(
                    phaseId,
                    new HudNode(
                            phaseId,
                            visibility,
                            "phase",
                            projection.publicView().phase()));
        }

        Map<String, List<PlayerAction>> byActionKey = new LinkedHashMap<>();
        for (Map.Entry<PlayerId, List<AuthorizedAction>> entry :
                projection.authorizedActions().entrySet()) {
            for (AuthorizedAction action : entry.getValue()) {
                byActionKey
                        .computeIfAbsent(action.legalAction().key(), ignored -> new ArrayList<>())
                        .add(new PlayerAction(entry.getKey(), action));
            }
        }
        List<SceneInteractionBinding> bindings = new ArrayList<>();
        int actionIndex = 0;
        for (Map.Entry<String, List<PlayerAction>> entry : byActionKey.entrySet()) {
            String actionKey = entry.getKey();
            InteractionHandle handle =
                    new InteractionHandle(
                            UUID.nameUUIDFromBytes(
                                    (projection.tableId()
                                                    + ":"
                                                    + projection.revision()
                                                    + ":"
                                                    + actionKey)
                                            .getBytes(StandardCharsets.UTF_8)));
            SceneNodeId id = new SceneNodeId("interaction/" + actionKey);
            nodes.put(
                    id,
                    new InteractionNode(
                            id,
                            SceneVisibility.publicToAll(),
                            handle,
                            layout.interaction(actionIndex++),
                            0.36,
                            0.18));
            for (PlayerAction action : entry.getValue()) {
                bindings.add(
                        new SceneInteractionBinding(
                                handle, action.player(), action.action().token()));
            }
        }
        return new SceneGraph(
                projection.tableId(), projection.revision(), nodes, bindings);
    }

    private static String tileAsset(RuleViewTile tile) {
        String value = tile.visualId().value();
        return value.contains(":") ? value : "mahjong:" + value;
    }

    private record PlayerAction(PlayerId player, AuthorizedAction action) {}
}
