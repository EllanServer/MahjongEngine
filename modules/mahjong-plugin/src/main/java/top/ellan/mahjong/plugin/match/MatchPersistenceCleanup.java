package top.ellan.mahjong.plugin.match;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.plugin.bootstrap.sql.DatabaseRuntime;

/** Removes durable table ownership after an actor has drained and closed. */
public final class MatchPersistenceCleanup {
    private MatchPersistenceCleanup() {}

    public static void closeMatch(
            DatabaseRuntime database, StartedRulePackMatch match, Instant closedAt) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(closedAt, "closedAt");
        database.matches().ifPresent(matches -> {
            try {
                matches.updateStatus(
                        match.binding().matchId(), TableLifecycle.CLOSED, closedAt);
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        });
        database.anchors().ifPresent(anchors -> {
            try {
                anchors.delete(match.tableId());
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        });
    }
}
