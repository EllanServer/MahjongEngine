package top.ellan.mahjong.plugin.bootstrap.rules;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.application.concurrent.FairRuleExecutor;
import top.ellan.mahjong.runtime.admin.RulePackAdminService;
import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.lifecycle.RulePackRuntime;
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

    public RulePackHotSwapService(
            RulePackAdminService admin, RulePackRuntime runtime, FairRuleExecutor rules) {
        this.admin = Objects.requireNonNull(admin, "admin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public String swap(RuleId ruleId, String version) throws Exception {
        // activateNow returns the durable selection, which already carries the verified coordinate.
        RulePackRef target = admin.activateNow(ruleId, version).active().get(ruleId);
        if (target == null || !target.version().equals(version)) {
            throw new RulePackException("Activation did not select " + ruleId + ':' + version);
        }
        return describe(runtime.promote(target), "replaced version fully unloaded");
    }

    public String deactivate(RuleId ruleId) throws Exception {
        admin.deactivate(ruleId);
        return describe(runtime.deactivate(ruleId), "unloaded");
    }

    public String rollback(RuleId ruleId) throws Exception {
        RulePackRef restored = admin.rollback(ruleId).active().get(ruleId);
        if (restored == null) {
            throw new RulePackException("Rollback did not restore an active version");
        }
        return describe(runtime.promote(restored), "restored " + restored.version());
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
