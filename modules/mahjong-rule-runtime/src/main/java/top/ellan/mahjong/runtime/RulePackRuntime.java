package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;

/** Restart-scoped classloader registry. It never hot-swaps a loaded provider. */
public final class RulePackRuntime implements AutoCloseable {
    private final RulePackPaths paths;
    private final RulePackLoader loader;
    private final RuleActivationStore activationStore;
    private final Map<RulePackRef, LoadedRulePack> loaded = new LinkedHashMap<>();
    private final Map<RuleId, RulePackRef> active = new LinkedHashMap<>();
    private final Map<RuleId, String> failures = new LinkedHashMap<>();
    private Map<RuleId, RulePackRef> pending = Map.of();
    private boolean started;

    public RulePackRuntime(
            RulePackPaths paths, RulePackLoader loader, RuleActivationStore activationStore) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.activationStore = Objects.requireNonNull(activationStore, "activationStore");
    }

    public synchronized RulePackRuntimeStatus start() throws IOException, RulePackException {
        if (started) {
            return status();
        }
        paths.createLayout();
        RuleActivationState selections = activationStore.promoteForStartup();
        pending = selections.pending();
        for (Map.Entry<RuleId, RulePackRef> entry : selections.active().entrySet()) {
            try {
                LoadedRulePack pack = loadPinned(entry.getValue());
                active.put(entry.getKey(), pack.reference());
            } catch (RulePackException failure) {
                failures.put(entry.getKey(), failure.getMessage());
            }
        }
        started = true;
        return status();
    }

    public synchronized Optional<RulePackProvider> providerForNewMatch(RuleId ruleId) {
        RulePackRef reference = active.get(Objects.requireNonNull(ruleId, "ruleId"));
        if (reference == null || failures.containsKey(ruleId)) {
            return Optional.empty();
        }
        LoadedRulePack pack = loaded.get(reference);
        return pack == null ? Optional.empty() : Optional.of(pack.provider());
    }

    public synchronized Optional<RulePackRef> activeReference(RuleId ruleId) {
        RulePackRef reference = active.get(Objects.requireNonNull(ruleId, "ruleId"));
        return failures.containsKey(ruleId) ? Optional.empty() : Optional.ofNullable(reference);
    }

    /** Resolves the exact version pinned by a recovered snapshot. */
    public synchronized RulePackProvider providerForPinnedMatch(RulePackRef reference)
            throws RulePackException {
        if (!started) {
            throw new IllegalStateException("RulePackRuntime has not started");
        }
        return loadPinned(reference).provider();
    }

    public synchronized RulePackRuntimeStatus status() {
        return new RulePackRuntimeStatus(active, pending, failures);
    }

    private LoadedRulePack loadPinned(RulePackRef reference) throws RulePackException {
        LoadedRulePack existing = loaded.get(reference);
        if (existing != null) {
            return existing;
        }
        if (!OfficialRuleIds.ALL.contains(reference.ruleId())) {
            throw new RulePackException("Pinned match references a non-official rule id");
        }
        LoadedRulePack loadedPack =
                loader.loadPinned(
                        paths.installedJar(reference.ruleId(), reference.version()), reference);
        loaded.put(reference, loadedPack);
        return loadedPack;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        for (LoadedRulePack pack : loaded.values()) {
            try {
                pack.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        loaded.clear();
        active.clear();
        started = false;
        if (failure != null) {
            throw failure;
        }
    }
}
