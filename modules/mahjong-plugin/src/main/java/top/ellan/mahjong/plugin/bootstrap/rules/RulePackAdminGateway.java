package top.ellan.mahjong.plugin.bootstrap.rules;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import top.ellan.mahjong.runtime.admin.RulePackInventory;
import top.ellan.mahjong.runtime.admin.RulePackVerification;
import top.ellan.mahjong.runtime.install.InstallationResult;
import top.ellan.mahjong.spi.RuleId;

/**
 * Asynchronous rule-pack administration gateway.
 *
 * <p>It moves every blocking {@link RulePackOperations} call onto the bounded IO executor so command
 * handlers never touch a region or actor thread, and so the composition root only supplies the
 * executor instead of also owning administration orchestration.</p>
 */
public final class RulePackAdminGateway {
    private final Supplier<RulePackOperations> operations;
    private final Executor io;

    public RulePackAdminGateway(Supplier<RulePackOperations> operations, Executor io) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.io = Objects.requireNonNull(io, "io");
    }

    public CompletionStage<RulePackInventory> listRules() {
        return submit(() -> operations.get().inventory().read());
    }

    public CompletionStage<InstallationResult> installRule(
            RuleId ruleId, Optional<String> version) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(version, "version");
        return submit(() -> operations.get().install(ruleId, version));
    }

    public CompletionStage<List<RulePackVerification>> verifyRules(Optional<RuleId> ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return submit(() -> operations.get().verify(ruleId));
    }

    /** Restart-scoped activation; the running JVM keeps its current selection. */
    public CompletionStage<Object> activateRule(RuleId ruleId, String version) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(version, "version");
        return submit(() -> operations.get().activate(ruleId, version));
    }

    /** Activates a version for new matches immediately; running matches keep their generation. */
    public CompletionStage<String> swapRule(RuleId ruleId, String version) {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(version, "version");
        return submit(() -> operations.get().swap(ruleId, version));
    }

    public CompletionStage<String> deactivateRule(RuleId ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return submit(() -> operations.get().deactivate(ruleId));
    }

    public CompletionStage<String> rollbackRule(RuleId ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return submit(() -> operations.get().rollback(ruleId));
    }

    public CompletionStage<List<Path>> collectRuleGarbage() {
        return submit(() -> operations.get().collectGarbage());
    }

    private <T> CompletionStage<T> submit(Callable<T> operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return operation.call();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                },
                io);
    }
}
