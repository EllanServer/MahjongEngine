package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strongly typed action rendering data; rule payloads remain opaque to the core.
 *
 * @param labelKey translation key used to label the action
 * @param placement client presentation area in which the action is offered
 * @param targetTile tile targeted by a hand-tile action, when applicable
 * @param emphasized whether the client should visually emphasize the action
 */
public record ActionPresentation(
        String labelKey,
        ActionPlacement placement,
        Optional<TileInstanceId> targetTile,
        boolean emphasized) {
    private static final Pattern VALID_LABEL = Pattern.compile("[a-z0-9][a-z0-9._:-]{0,95}");

    /**
     * Creates validated client presentation metadata for an action.
     *
     * @param labelKey translation key used to label the action
     * @param placement client presentation area in which the action is offered
     * @param targetTile tile targeted by a hand-tile action, when applicable
     * @param emphasized whether the client should visually emphasize the action
     */
    public ActionPresentation {
        labelKey = Objects.requireNonNull(labelKey, "labelKey");
        Objects.requireNonNull(placement, "placement");
        targetTile = Objects.requireNonNull(targetTile, "targetTile");
        if (!VALID_LABEL.matcher(labelKey).matches()) {
            throw new IllegalArgumentException("Invalid action label key: " + labelKey);
        }
        if (placement == ActionPlacement.HAND_TILE && targetTile.isEmpty()) {
            throw new IllegalArgumentException("HAND_TILE actions require a target tile");
        }
        if (placement != ActionPlacement.HAND_TILE && targetTile.isPresent()) {
            throw new IllegalArgumentException("Only HAND_TILE actions may target a tile");
        }
    }

    /**
     * Creates a non-emphasized primary-row action.
     *
     * @param labelKey translation key used to label the action
     * @return action-row presentation metadata
     */
    public static ActionPresentation actionRow(String labelKey) {
        return new ActionPresentation(
                labelKey, ActionPlacement.ACTION_ROW, Optional.empty(), false);
    }

    /**
     * Creates a non-emphasized action attached to one hand tile.
     *
     * @param labelKey translation key used to label the action
     * @param targetTile physical tile targeted by the action
     * @return hand-tile presentation metadata
     */
    public static ActionPresentation handTile(String labelKey, TileInstanceId targetTile) {
        return new ActionPresentation(
                labelKey,
                ActionPlacement.HAND_TILE,
                Optional.of(Objects.requireNonNull(targetTile, "targetTile")),
                false);
    }

    /**
     * Creates a non-emphasized secondary-row action.
     *
     * @param labelKey translation key used to label the action
     * @return secondary-row presentation metadata
     */
    public static ActionPresentation secondaryRow(String labelKey) {
        return new ActionPresentation(
                labelKey, ActionPlacement.SECONDARY_ROW, Optional.empty(), false);
    }

    /**
     * Returns an emphasized copy of this presentation.
     *
     * @return this instance when already emphasized, otherwise an emphasized copy
     */
    public ActionPresentation withEmphasis() {
        return emphasized
                ? this
                : new ActionPresentation(labelKey, placement, targetTile, true);
    }
}
