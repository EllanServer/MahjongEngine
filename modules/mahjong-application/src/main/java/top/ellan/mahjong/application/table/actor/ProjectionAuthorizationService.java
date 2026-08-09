package top.ellan.mahjong.application.table.actor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.security.ActionTokenIssuer;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;

/** Adds core-owned, revision-bound authority to a provider's legal actions. */
final class ProjectionAuthorizationService {
    private final ActionTokenIssuer tokenIssuer;

    ProjectionAuthorizationService(ActionTokenIssuer tokenIssuer) {
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
    }

    AuthorizedProjection authorize(
            TableAggregate aggregate,
            RuleFrame frame) {
        Map<UUID, AuthorizedAction> catalog = new HashMap<>();
        Map<PlayerId, List<AuthorizedAction>> authorizedByPlayer = new LinkedHashMap<>();
        frame.legalActions()
                .forEach(
                        (player, actions) -> {
                            List<AuthorizedAction> authorized = new ArrayList<>(actions.size());
                            for (LegalAction action : actions) {
                                ActionToken token = tokenIssuer.issue(player, aggregate.revision());
                                AuthorizedAction item = new AuthorizedAction(token, action);
                                if (catalog.put(token.value(), item) != null) {
                                    throw new IllegalStateException("Action token collision");
                                }
                                authorized.add(item);
                            }
                            authorizedByPlayer.put(player, List.copyOf(authorized));
                        });
        TableProjection projection =
                new TableProjection(
                        aggregate.tableId(),
                        aggregate.revision(),
                        aggregate.lifecycle(),
                        frame.publicView(),
                        frame.privateViews(),
                        authorizedByPlayer);
        return new AuthorizedProjection(projection, catalog);
    }
}
