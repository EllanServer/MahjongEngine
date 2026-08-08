package top.ellan.mahjong.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;

/** Immutable actor output; infrastructure may process it asynchronously. */
public record TableProjection(
        TableId tableId,
        long revision,
        TableLifecycle lifecycle,
        PublicRuleView publicView,
        Map<PlayerId, PrivateRuleView> privateViews,
        Map<PlayerId, List<AuthorizedAction>> authorizedActions) {
    public TableProjection {
        Objects.requireNonNull(tableId, "tableId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(publicView, "publicView");
        privateViews = Map.copyOf(Objects.requireNonNull(privateViews, "privateViews"));
        Objects.requireNonNull(authorizedActions, "authorizedActions");
        Map<PlayerId, List<AuthorizedAction>> copied = new LinkedHashMap<>();
        authorizedActions.forEach((player, actions) -> copied.put(player, List.copyOf(actions)));
        authorizedActions = Map.copyOf(copied);
    }
}
