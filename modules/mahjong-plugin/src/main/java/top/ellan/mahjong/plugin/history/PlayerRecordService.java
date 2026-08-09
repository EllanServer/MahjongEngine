package top.ellan.mahjong.plugin.history;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import top.ellan.mahjong.application.history.PlayerMatchHistoryEntry;
import top.ellan.mahjong.application.history.PlayerRankingPage;
import top.ellan.mahjong.application.history.PlayerRecordQueryPort;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleId;

/** Runs bounded player-record queries without exposing blocking ports to command handlers. */
public final class PlayerRecordService {
    private final Executor ioExecutor;
    private final Supplier<Optional<PlayerRecordQueryPort>> queries;

    public PlayerRecordService(
            Executor ioExecutor, Supplier<Optional<PlayerRecordQueryPort>> queries) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    public CompletionStage<List<PlayerMatchHistoryEntry>> history(
            PlayerId playerId, int page, int pageSize) {
        Objects.requireNonNull(playerId, "playerId");
        if (page < 1 || pageSize < 1 || pageSize > 50) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid history page bounds"));
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, pageSize);
        } catch (ArithmeticException failure) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("History page is too large", failure));
        }
        return supply(() -> requireQueries().history(playerId, offset, pageSize));
    }

    public CompletionStage<PlayerRankingPage> ranking(
            PlayerId playerId, RuleId ruleId, int page, int pageSize) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(ruleId, "ruleId");
        return supply(() -> requireQueries().ranking(playerId, ruleId, page, pageSize));
    }

    private PlayerRecordQueryPort requireQueries() {
        return queries.get()
                .orElseThrow(() -> new IllegalStateException("Player records are unavailable"));
    }

    private <T> CompletionStage<T> supply(CheckedSupplier<T> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return operation.get();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                },
                ioExecutor);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
