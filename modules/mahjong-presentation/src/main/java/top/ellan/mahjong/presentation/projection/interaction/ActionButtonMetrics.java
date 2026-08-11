package top.ellan.mahjong.presentation.projection.interaction;

import top.ellan.mahjong.presentation.label.ActionLabelPolicy;
import top.ellan.mahjong.presentation.label.ActionLabelText;
import top.ellan.mahjong.spi.PlayerId;

/** Cold-path localized width resolver for one player's action buttons. */
@FunctionalInterface
public interface ActionButtonMetrics {
    double width(PlayerId player, String labelKey);

    static ActionButtonMetrics fallback() {
        return (player, labelKey) -> ActionLabelPolicy.buttonWidth(
                ActionLabelText.resolve(labelKey, (key, fallback) -> fallback));
    }
}
