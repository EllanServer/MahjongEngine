package top.ellan.mahjong.application.table.actor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.ScheduledRuleAction;

/** Immutable rule output before the core adds authorization tokens. */
record RuleFrame(
        PublicRuleView publicView,
        Map<PlayerId, PrivateRuleView> privateViews,
        Map<PlayerId, List<LegalAction>> legalActions,
        Optional<ScheduledRuleAction> scheduledAction) {
    RuleFrame {
        Objects.requireNonNull(publicView, "publicView");
        privateViews = Map.copyOf(privateViews);
        Map<PlayerId, List<LegalAction>> copy = new LinkedHashMap<>();
        for (Map.Entry<PlayerId, List<LegalAction>> entry : legalActions.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        legalActions = Map.copyOf(copy);
        scheduledAction = Objects.requireNonNull(scheduledAction, "scheduledAction");
    }
}
