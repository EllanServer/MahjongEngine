package top.ellan.mahjong.plugin.bootstrap.rules;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.runtime.admin.RulePackAdminService;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.lifecycle.RulePackRuntime;
import top.ellan.mahjong.runtime.resources.InspectedRuleResourcePack;
import top.ellan.mahjong.runtime.resources.RuleResourcePackResolver;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RulePackRef;

/**
 * Blocking hot-swap orchestration. Callers must run it on the bounded IO executor.
 *
 * <p>The durable selection is written first so a crash between the two steps still starts with the
 * intended version. The runtime is then told which generation to hand to new matches; a generation
 * that still has leases becomes superseded instead of being closed underneath a running match.</p>
 */
public final class RulePackHotSwapService {
    private final RulePackAdminService admin;
    private final RulePackRuntime runtime;
    private final FairRuleExecutor rules;
    private final RuleResourcePackResolver resourcePacks;
    private final RuleResourceActivator resourceActivator;

    public RulePackHotSwapService(
            RulePackAdminService admin,
            RulePackRuntime runtime,
            FairRuleExecutor rules,
            RuleResourcePackResolver resourcePacks,
            RuleResourceActivator resourceActivator) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.resourcePacks = Objects.requireNonNull(resourcePacks, "resourcePacks");
        this.resourceActivator = Objects.requireNonNull(resourceActivator, "resourceActivator");
    }

    public String swap(RuleId ruleId, String version) throws Exception {
        Optional<RulePackRef> previous = runtime.activeReference(ruleId);
        boolean selectionChanged = false;
        RulePackRef target = null;
        try {
            // activateNow returns the durable selection, which already carries the verified coordinate.
            target = admin.activateNow(ruleId, version).active().get(ruleId);
            selectionChanged = true;
            if (target == null || !target.version().equals(version)) {
                throw new RulePackException("Activation did not select " + ruleId + ':' + version);
            }
            Optional<InspectedRuleResourcePack> resources = resourcePacks.resolve(target);
            resourceActivator.activate(target, resources);
            Optional<RulePackRef> stillRunning = runtime.promote(target);
            return describe(stillRunning, "replaced version fully unloaded");
        } catch (Exception failure) {
            if (selectionChanged) {
                restoreSelection(ruleId, previous, failure);
            }
            removeFailedTarget(target, previous, failure);
            restoreResource(previous, failure);
            throw failure;
        }
    }

    public String deactivate(RuleId ruleId) throws Exception {
        runtime.activeReference(ruleId)
                .orElseThrow(() -> new RulePackException("Rule is not active: " + ruleId));
        admin.deactivate(ruleId);
        Optional<RulePackRef> stillRunning = runtime.deactivate(ruleId);
        return describe(stillRunning, "unloaded");
    }

    private void restoreSelection(
            RuleId ruleId, Optional<RulePackRef> previous, Exception original) {
        try {
            if (previous.isPresent()) {
                admin.rollback(ruleId);
            } else {
                admin.deactivate(ruleId);
            }
        } catch (Exception restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
    }

    private void restoreResource(Optional<RulePackRef> previous, Exception original) {
        try {
            Optional<InspectedRuleResourcePack> resource = previous.isEmpty()
                    ? Optional.empty()
                    : resourcePacks.resolve(previous.orElseThrow());
            if (previous.isPresent()) {
                resourceActivator.activate(previous.orElseThrow(), resource);
            }
        } catch (Exception restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
    }

    public String rollback(RuleId ruleId) throws Exception {
        Optional<RulePackRef> current = runtime.activeReference(ruleId);
        RulePackRef target = admin.rollbackTarget(ruleId);
        boolean selectionChanged = false;
        try {
            RulePackRef restored = admin.rollback(ruleId).active().get(ruleId);
            selectionChanged = true;
            if (!target.equals(restored)) {
                throw new RulePackException("Rollback did not restore the verified coordinate");
            }
            Optional<InspectedRuleResourcePack> targetResources = resourcePacks.resolve(target);
            resourceActivator.activate(target, targetResources);
            Optional<RulePackRef> stillRunning = runtime.promote(restored);
            return describe(stillRunning, "restored " + restored.version());
        } catch (Exception failure) {
            if (selectionChanged) {
                restoreSelection(ruleId, current, failure);
            }
            removeFailedTarget(target, current, failure);
            restoreResource(current, failure);
            throw failure;
        }
    }

    private void removeFailedTarget(
            RulePackRef target, Optional<RulePackRef> previous, Exception original) {
        if (target == null || previous.filter(target::equals).isPresent()) {
            return;
        }
        try {
            resourceActivator.activate(target, Optional.empty());
        } catch (Exception restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
    }

    /**
     * Renews rule worker threads once a generation is gone so no thread local keeps its classloader
     * alive, then reports whether the loader was actually reclaimed.
     */
    private String describe(Optional<RulePackRef> stillRunning, String unloadedDetail) {
        if (stillRunning.isPresent()) {
            return stillRunning.orElseThrow().version() + " still serving running matches";
        }
        rules.renewWorkers();
        return unloadedDetail;
    }

    /** Confirms one unloaded coordinate's classloader became unreachable. */
    public static boolean classLoaderReclaimed(WeakReference<ClassLoader> watch) {
        for (int attempt = 0; attempt < 3 && watch.get() != null; attempt++) {
            System.gc();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return watch.get() == null;
    }
}
