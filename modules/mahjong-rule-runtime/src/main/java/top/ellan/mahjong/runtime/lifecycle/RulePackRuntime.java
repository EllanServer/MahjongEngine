package top.ellan.mahjong.runtime.lifecycle;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.runtime.activation.RuleActivationState;
import top.ellan.mahjong.runtime.activation.RuleActivationStore;
import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.loading.LoadedRulePack;
import top.ellan.mahjong.runtime.loading.PinnedRulePackLoader;
import top.ellan.mahjong.runtime.storage.RulePackPaths;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * Classloader registry that supports two generations of one rule at the same time.
 *
 * <p>New matches always receive the active generation. A version replaced while matches are still
 * running becomes superseded: it stays loaded and reachable through its pinned reference until every
 * lease is released, then it can be unloaded. This mirrors parallel deployment in servlet
 * containers, where a match plays the role of a session.</p>
 */
public final class RulePackRuntime implements AutoCloseable {
    private final RulePackPaths paths;
    private final PinnedRulePackLoader loader;
    private final RuleActivationStore activationStore;
    private final Map<RulePackRef, LoadedRulePack> loaded = new LinkedHashMap<>();
    private final Map<RuleId, RulePackRef> active = new LinkedHashMap<>();
    private final Map<RuleId, RulePackRef> superseded = new LinkedHashMap<>();
    private final Map<RuleId, String> failures = new LinkedHashMap<>();
    private Map<RuleId, RulePackRef> pending = Map.of();
    private boolean started;

    public RulePackRuntime(
            RulePackPaths paths, PinnedRulePackLoader loader, RuleActivationStore activationStore) {
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

    /** Resolves the exact version pinned by a recovered snapshot, including superseded ones. */
    public synchronized RulePackProvider providerForPinnedMatch(RulePackRef reference)
            throws RulePackException {
        if (!started) {
            throw new IllegalStateException("RulePackRuntime has not started");
        }
        return loadPinned(reference).provider();
    }

    /**
     * Registers one holder against a loaded coordinate so the version cannot be unloaded while a
     * match is using it.
     */
    public synchronized void acquire(RulePackRef reference, Object holder) {
        LoadedRulePack pack = loaded.get(Objects.requireNonNull(reference, "reference"));
        if (pack == null) {
            throw new IllegalStateException("Rule pack is not loaded: " + reference);
        }
        pack.acquire(holder);
    }

    /** Releases one holder; a superseded version with no remaining leases is unloaded at once. */
    public synchronized void release(RulePackRef reference, Object holder) {
        LoadedRulePack pack = loaded.get(Objects.requireNonNull(reference, "reference"));
        if (pack == null) {
            return;
        }
        if (pack.release(holder) && isSuperseded(reference)) {
            try {
                unload(reference);
            } catch (RulePackException ignored) {
                // A lease raced back in; the pack stays loaded and can be unloaded later.
            }
        }
    }

    /**
     * Makes one already loaded version the target for new matches and marks the replaced version
     * superseded. Returns the replaced coordinate when a running generation remains.
     */
    public synchronized Optional<RulePackRef> promote(RulePackRef reference)
            throws RulePackException {
        Objects.requireNonNull(reference, "reference");
        if (!started) {
            throw new IllegalStateException("RulePackRuntime has not started");
        }
        LoadedRulePack pack = loadPinned(reference);
        RulePackRef replaced = active.put(reference.ruleId(), pack.reference());
        failures.remove(reference.ruleId());
        pending = withoutRule(pending, reference.ruleId());
        if (replaced == null || replaced.equals(pack.reference())) {
            return Optional.empty();
        }
        LoadedRulePack old = loaded.get(replaced);
        if (old == null || !old.inUse()) {
            unloadQuietly(replaced);
            return Optional.empty();
        }
        superseded.put(replaced.ruleId(), replaced);
        return Optional.of(replaced);
    }

    /** Stops handing this rule to new matches. Running matches keep their provider. */
    public synchronized Optional<RulePackRef> deactivate(RuleId ruleId) throws RulePackException {
        RulePackRef removed = active.remove(Objects.requireNonNull(ruleId, "ruleId"));
        if (removed == null) {
            throw new RulePackException("Rule is not active: " + ruleId);
        }
        LoadedRulePack pack = loaded.get(removed);
        if (pack == null || !pack.inUse()) {
            unloadQuietly(removed);
            return Optional.empty();
        }
        superseded.put(ruleId, removed);
        return Optional.of(removed);
    }

    /**
     * Closes one unreferenced coordinate and returns a weak handle to its classloader so the caller
     * can confirm the loader was reclaimed rather than leaked.
     */
    public synchronized WeakReference<ClassLoader> unload(RulePackRef reference)
            throws RulePackException {
        Objects.requireNonNull(reference, "reference");
        if (reference.equals(active.get(reference.ruleId()))) {
            throw new RulePackException("Deactivate the rule before unloading: " + reference);
        }
        LoadedRulePack pack = loaded.get(reference);
        if (pack == null) {
            throw new RulePackException("Rule pack is not loaded: " + reference);
        }
        if (pack.inUse()) {
            throw new RulePackException(
                    "Rule pack still has " + pack.leaseCount() + " match lease(s): " + reference);
        }
        WeakReference<ClassLoader> watch = pack.classLoaderWatch();
        try {
            pack.close();
        } catch (IOException failure) {
            throw new RulePackException("Unable to close rule-pack classloader", failure);
        }
        loaded.remove(reference);
        superseded.remove(reference.ruleId(), reference);
        return watch;
    }

    /** Coordinates that are still loaded, so garbage collection must not move their artifacts. */
    public synchronized List<RulePackRef> loadedReferences() {
        return List.copyOf(loaded.keySet());
    }

    public synchronized RulePackRuntimeStatus status() {
        Map<RuleId, RulePackRef> running = new LinkedHashMap<>();
        superseded.forEach(
                (ruleId, reference) -> {
                    LoadedRulePack pack = loaded.get(reference);
                    if (pack != null && pack.inUse()) {
                        running.put(ruleId, reference);
                    }
                });
        return new RulePackRuntimeStatus(active, pending, failures, running);
    }

    private boolean isSuperseded(RulePackRef reference) {
        return reference.equals(superseded.get(reference.ruleId()));
    }

    private void unloadQuietly(RulePackRef reference) {
        LoadedRulePack pack = loaded.remove(reference);
        superseded.remove(reference.ruleId(), reference);
        if (pack == null) {
            return;
        }
        try {
            pack.close();
        } catch (IOException | RuntimeException ignored) {
            // Closing is best effort here; the coordinate is already out of every index.
        }
    }

    private static Map<RuleId, RulePackRef> withoutRule(
            Map<RuleId, RulePackRef> source, RuleId ruleId) {
        if (!source.containsKey(ruleId)) {
            return source;
        }
        Map<RuleId, RulePackRef> copy = new LinkedHashMap<>(source);
        copy.remove(ruleId);
        return Map.copyOf(copy);
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
                // Shutdown discards every lease; matches are drained before this point.
                pack.releaseAll();
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
        superseded.clear();
        started = false;
        if (failure != null) {
            throw failure;
        }
    }
}
