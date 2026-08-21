package top.ellan.mahjong.presentation.node;

import java.util.Objects;
import java.util.Set;

/** Selects CE furniture only for labels declared by the bundled CE resource pack. */
public final class ActionLabelNodes {
    private static final Set<String> RESOURCE_LABELS = Set.of(
            "action.added_kong",
            "action.chii",
            "action.chow",
            "action.close_reactions",
            "action.concealed_kong",
            "action.declare_heavenly_missing",
            "action.declare_missing",
            "action.declare_nine_terminals",
            "action.declare_self_kan",
            "action.declare_tsumo",
            "action.direct_kong",
            "action.discard",
            "action.discard_riichi",
            "action.draw",
            "action.hu",
            "action.instant_rain",
            "action.leave",
            "action.minkan",
            "action.pass",
            "action.pon",
            "action.pung",
            "action.ready",
            "action.ron",
            "action.self_draw_win",
            "action.settle_exhaustion",
            "action.skip",
            "action.start",
            "action.start_next_hand",
            "action.transfer_owner_east",
            "action.transfer_owner_north",
            "action.transfer_owner_south",
            "action.transfer_owner_west",
            "action.unready",
            "action.view_river",
            "action.win");

    private ActionLabelNodes() {}

    /** Labels whose CE furniture definitions are guaranteed by the core resource pack. */
    public static Set<String> resourceLabels() {
        return RESOURCE_LABELS;
    }

    public static SceneNode create(
            SceneNodeId id,
            SceneVisibility visibility,
            String labelKey,
            SceneTransform transform,
            boolean emphasized) {
        Objects.requireNonNull(labelKey, "labelKey");
        return RESOURCE_LABELS.contains(labelKey)
                ? new ActionFurnitureNode(id, visibility, labelKey, transform, emphasized)
                : new ActionLabelNode(id, visibility, labelKey, transform, emphasized);
    }
}
