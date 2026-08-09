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
    private static final int ACTION_SLOTS = 64;
    private static final String TABLE_ASSET = "mahjongpaper:table_visual";
    private static final String SEAT_ASSET = "mahjongpaper:seat_chair";
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
        addFixedTableFurniture(nodes);
        for (RuleViewTile tile : projection.publicView().tiles()) {
            SceneNodeId id = new SceneNodeId("tile/public/" + tile.instanceId().value());
            String asset = tile.faceUp() ? tileFurnitureAsset(tile) : tileBackAsset;
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
            SceneNodeId actionsId =
                    new SceneNodeId("hud/" + viewer.toString().replace("-", "") + "/actions");
            String actionKeys = projection.authorizedActions().getOrDefault(viewer, List.of()).stream()
                    .map(action -> action.legalAction().key())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            nodes.put(
                    actionsId,
                    new HudNode(actionsId, visibility, "actions", actionKeys));
        }

        List<SceneInteractionBinding> bindings = new ArrayList<>();
        List<InteractionHandle> handles = new ArrayList<>(ACTION_SLOTS);
        for (int slot = 0; slot < ACTION_SLOTS; slot++) {
            InteractionHandle handle =
                    new InteractionHandle(
                            UUID.nameUUIDFromBytes(
                                    (projection.tableId() + ":action-slot:" + slot)
                                            .getBytes(StandardCharsets.UTF_8)));
            handles.add(handle);
            SceneNodeId id = new SceneNodeId("interaction/slot-" + slot);
            nodes.put(
                    id,
                    new InteractionNode(
                            id,
                            SceneVisibility.publicToAll(),
                            handle,
                            layout.interaction(slot),
                            0.36,
                            0.18));
        }
        for (Map.Entry<PlayerId, List<AuthorizedAction>> entry :
                projection.authorizedActions().entrySet()) {
            List<AuthorizedAction> actions = entry.getValue().stream()
                    .sorted(java.util.Comparator.comparing(action -> action.legalAction().key()))
                    .toList();
            if (actions.size() > ACTION_SLOTS) {
                throw new IllegalArgumentException("Projection exceeds fixed interaction slots");
            }
            for (int slot = 0; slot < actions.size(); slot++) {
                AuthorizedAction action = actions.get(slot);
                bindings.add(
                        new SceneInteractionBinding(
                                handles.get(slot), entry.getKey(), action.token()));
            }
        }
        return new SceneGraph(
                projection.tableId(), projection.revision(), nodes, bindings);
    }

    private static void addFixedTableFurniture(Map<SceneNodeId, SceneNode> nodes) {
        SceneNodeId tableId = new SceneNodeId("furniture/table");
        nodes.put(
                tableId,
                new FurnitureNode(
                        tableId,
                        SceneVisibility.publicToAll(),
                        TABLE_ASSET,
                        new SceneTransform(0, 0, 0, 0, 0, 0, 1),
                        48));
        for (int seat = 0; seat < 4; seat++) {
            double angle = Math.toRadians(seat * 90.0);
            SceneNodeId id = new SceneNodeId("furniture/seat-" + seat);
            nodes.put(
                    id,
                    new FurnitureNode(
                            id,
                            SceneVisibility.publicToAll(),
                            SEAT_ASSET,
                            new SceneTransform(
                                    Math.sin(angle) * 2.25,
                                    0,
                                    Math.cos(angle) * 2.25,
                                    seat * 90.0 + 180.0,
                                    0,
                                    0,
                                    1),
                            48));
        }
    }

    private static String tileFurnitureAsset(RuleViewTile tile) {
        String value = tile.visualId().value();
        int separator = value.indexOf("tile/");
        String tileName = normalizeTileName(separator >= 0 ? value.substring(separator + 5) : value);
        if (!tileName.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported tile visual id: " + value);
        }
        String pose = switch (tile.zone()) {
            case HAND, WALL -> "tile_standing";
            case DISCARD, MELD, INDICATOR, POINT_STICK, AUXILIARY -> "tile_flat_face_up";
        };
        return "mahjongpaper:" + pose + '_' + tileName;
    }

    /** Maps rule-pack notation to the stable CraftEngine asset vocabulary. */
    public static String normalizeTileName(String rawName) {
        Objects.requireNonNull(rawName, "rawName");
        if (rawName.matches("[1-9][mps]r")) {
            return rawName.charAt(1) + rawName.substring(0, 1) + "_red";
        }
        if (rawName.matches("[1-9][mps]")) {
            return rawName.charAt(1) + rawName.substring(0, 1);
        }
        if (rawName.matches("[1-7]z")) {
            return switch (rawName.charAt(0)) {
                case '1' -> "east";
                case '2' -> "south";
                case '3' -> "west";
                case '4' -> "north";
                case '5' -> "white_dragon";
                case '6' -> "green_dragon";
                case '7' -> "red_dragon";
                default -> throw new IllegalArgumentException("Unsupported honor notation");
            };
        }
        return rawName;
    }
}
