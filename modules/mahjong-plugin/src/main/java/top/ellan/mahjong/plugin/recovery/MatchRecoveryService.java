package top.ellan.mahjong.plugin.recovery;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.application.table.TableActorRegistry;
import top.ellan.mahjong.domain.CompetitionRef;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.anchor.JdbcTableAnchorRepository;
import top.ellan.mahjong.persistence.sql.match.MatchInstanceRecord;
import top.ellan.mahjong.platform.paper.PaperTableAnchorService;
import top.ellan.mahjong.plugin.table.LiveTableDirectory;
import top.ellan.mahjong.plugin.match.RulePackMatchCoordinator;
import top.ellan.mahjong.plugin.runtime.FailureSupport;

/** Restores independent matches and isolates every failed table from the remaining runtime. */
public final class MatchRecoveryService {
    private final PaperTableAnchorService anchors;
    private final LiveTableDirectory liveTables;
    private final TableActorRegistry actors;
    private final Executor ioExecutor;
    private final Clock clock;
    private final Logger logger;

    public MatchRecoveryService(
            PaperTableAnchorService anchors,
            LiveTableDirectory liveTables,
            TableActorRegistry actors,
            Executor ioExecutor,
            Clock clock,
            Logger logger) {
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.liveTables = Objects.requireNonNull(liveTables, "liveTables");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletionStage<Void> recover(
            JdbcMatchRepository matches,
            JdbcTableAnchorRepository anchorRepository,
            RulePackMatchCoordinator coordinator) {
        Objects.requireNonNull(matches, "matches");
        Objects.requireNonNull(anchorRepository, "anchorRepository");
        Objects.requireNonNull(coordinator, "coordinator");
        List<MatchInstanceRecord> recoverable;
        Map<TableId, TableAnchor> anchorsByTable = new HashMap<>();
        try {
            recoverable = matches.recoverableMatches();
            anchorRepository.list().forEach(value -> anchorsByTable.put(value.tableId(), value));
        } catch (SQLException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        List<CompletableFuture<Void>> recoveries =
                recoverable.stream()
                        .map(
                                match ->
                                        recoverOne(
                                                        match,
                                                        anchorsByTable.get(match.tableId()),
                                                        coordinator,
                                                        matches)
                                                .exceptionally(
                                                        failure -> {
                                                            logger.log(
                                                                    Level.WARNING,
                                                                    "Table recovery isolated: "
                                                                            + match.tableId(),
                                                                    FailureSupport.unwrap(failure));
                                                            return null;
                                                        })
                                                .toCompletableFuture())
                        .toList();
        return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
    }

    public void blockRecoverableMatches(JdbcMatchRepository matches) {
        Objects.requireNonNull(matches, "matches");
        try {
            for (MatchInstanceRecord match : matches.recoverableMatches()) {
                if (match.status() != TableLifecycle.NEEDS_ADMIN_REVIEW) {
                    matches.updateStatus(
                            match.binding().matchId(),
                            TableLifecycle.BLOCKED_RULE_PACK,
                            Instant.now(clock));
                }
            }
        } catch (SQLException failure) {
            logger.log(Level.WARNING, "Could not block recoverable matches", failure);
        }
    }

    private CompletionStage<Void> recoverOne(
            MatchInstanceRecord match,
            TableAnchor anchor,
            RulePackMatchCoordinator coordinator,
            JdbcMatchRepository matches) {
        if (match.status() == TableLifecycle.NEEDS_ADMIN_REVIEW) {
            return CompletableFuture.completedFuture(null);
        }
        if (anchor == null) {
            return markReview(match, "missing table anchor", matches);
        }
        return anchors.restore(anchor)
                .thenCompose(
                        ignored ->
                                coordinator.recover(
                                        match.binding().matchId(), CompetitionRef.none()))
                .thenCompose(
                        startedMatch -> {
                            if (liveTables.registerRecovered(startedMatch)) {
                                return CompletableFuture.completedFuture(null);
                            }
                            actors.remove(startedMatch.tableId(), startedMatch.actor());
                            startedMatch.actor().close();
                            return markReview(
                                    match,
                                    "participant or table recovery conflict",
                                    matches);
                        });
    }

    private CompletionStage<Void> markReview(
            MatchInstanceRecord match,
            String reason,
            JdbcMatchRepository repository) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        repository.updateStatus(
                                match.binding().matchId(),
                                TableLifecycle.NEEDS_ADMIN_REVIEW,
                                Instant.now(clock));
                    } catch (SQLException failure) {
                        throw new CompletionException(failure);
                    }
                    logger.warning(
                            "Match "
                                    + match.binding().matchId()
                                    + " requires review: "
                                    + reason);
                },
                ioExecutor);
    }
}
