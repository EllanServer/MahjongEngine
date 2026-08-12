package top.ellan.mahjong.plugin.match;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.plugin.bootstrap.rules.RulePackRuntimeServices;
import top.ellan.mahjong.plugin.bootstrap.sql.DatabaseRuntime;

/** Removes durable table ownership and the rule-pack lease after an actor drained and closed. */
public final class MatchPersistenceCleanup {
    private MatchPersistenceCleanup() {}

    /**
     * Releases this table's hold on the generation it was bound to.
     *
     * <p>Only safe once the actor has drained, because until then rule code may still run for this
     * table. A superseded generation whose last lease is released here is unloaded immediately.</p>
     */
    public static void releaseRulePackLease(
            RulePackRuntimeServices rules, StartedRulePackMatch match, TableId tableId) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(tableId, "tableId");
        rules.runtime().ifPresent(runtime -> runtime.release(match.binding().rulePack(), tableId));
    }

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
        database.lobbies().ifPresent(lobbies -> {
            try {
                lobbies.delete(match.tableId());
            } catch (Exception failure) {
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

    /** Marks an abruptly ended match closed while retaining its reusable lobby and anchor. */
    public static void closeMatchRecord(
            DatabaseRuntime database, StartedRulePackMatch match, Instant closedAt) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(closedAt, "closedAt");
        database.matches().ifPresent(
                matches -> {
                    try {
                        matches.updateStatus(
                                match.binding().matchId(), TableLifecycle.CLOSED, closedAt);
                    } catch (SQLException failure) {
                        throw new CompletionException(failure);
                    }
                });
    }
}
