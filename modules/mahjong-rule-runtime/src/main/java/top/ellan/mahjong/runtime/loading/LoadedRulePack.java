package top.ellan.mahjong.runtime.loading;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * Provider and classloader held for as long as any match leases this artifact.
 *
 * <p>Leases are what make a running replacement safe: a superseded version stays loaded while its
 * matches finish, and only an unreferenced pack may be closed. Class unloading additionally
 * requires the defining loader to become unreachable, so {@link #classLoaderWatch()} lets callers
 * prove the loader was actually reclaimed instead of assuming it.</p>
 */
public final class LoadedRulePack implements AutoCloseable {
    private final RulePackRef reference;
    private final Path artifact;
    private final RulePackProvider provider;
    private final ChildFirstRuleClassLoader classLoader;
    private final Set<Object> leases = new LinkedHashSet<>();
    private boolean closed;

    LoadedRulePack(
            RulePackRef reference,
            Path artifact,
            RulePackProvider provider,
            ChildFirstRuleClassLoader classLoader) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    public RulePackRef reference() {
        return reference;
    }

    public Path artifact() {
        return artifact;
    }

    public RulePackProvider provider() {
        return provider;
    }

    /** Registers one holder, usually a match id. Repeated acquisition by the same holder is idempotent. */
    public synchronized void acquire(Object holder) {
        Objects.requireNonNull(holder, "holder");
        if (closed) {
            throw new IllegalStateException("Rule pack is already closed: " + reference);
        }
        leases.add(holder);
    }

    /** Releases one holder and reports whether the pack became unreferenced. */
    public synchronized boolean release(Object holder) {
        leases.remove(Objects.requireNonNull(holder, "holder"));
        return leases.isEmpty();
    }

    public synchronized int leaseCount() {
        return leases.size();
    }

    public synchronized boolean inUse() {
        return !leases.isEmpty();
    }

    public synchronized boolean closed() {
        return closed;
    }

    /** Drops every lease. Only shutdown may use this; matches are drained before it runs. */
    public synchronized void releaseAll() {
        leases.clear();
    }

    /**
     * Weak handle used to verify the loader is reclaimable after {@link #close()}. It never keeps
     * the loader alive by itself.
     */
    public WeakReference<ClassLoader> classLoaderWatch() {
        return new WeakReference<>(classLoader);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (!leases.isEmpty()) {
            throw new IllegalStateException(
                    "Rule pack still has " + leases.size() + " lease(s): " + reference);
        }
        closed = true;
        classLoader.close();
    }
}
