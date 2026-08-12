package top.ellan.mahjong.plugin.bootstrap.rules;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.plugin.runtime.RuleExecutionPools;
import top.ellan.mahjong.runtime.admin.RulePackAdminService;
import top.ellan.mahjong.runtime.admin.RulePackInventoryReader;
import top.ellan.mahjong.runtime.admin.RulePackVerification;
import top.ellan.mahjong.runtime.install.InstallationResult;
import top.ellan.mahjong.spi.RuleId;

/**
 * Blocking rule-pack administration facade.
 *
 * <p>Every method must run on the bounded IO executor. It exists so the composition root only wires
 * dependencies instead of also owning administration and hot-swap orchestration.</p>
 */
public final class RulePackOperations {
    private final RulePackRuntimeServices services;
    private final RuleExecutionPools executors;
    private final RuleResourceActivator resources;

    public RulePackOperations(
            RulePackRuntimeServices services,
            RuleExecutionPools executors,
            RuleResourceActivator resources) {
        this.services = Objects.requireNonNull(services, "services");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public RulePackInventoryReader inventory() {
        return services.inventory();
    }

    public InstallationResult install(RuleId ruleId, Optional<String> version) throws Exception {
        return admin().install(ruleId, version);
    }

    public List<RulePackVerification> verify(Optional<RuleId> ruleId) throws Exception {
        return admin().verify(ruleId);
    }

    /** Restart-scoped activation; the running JVM keeps its current selection. */
    public Object activate(RuleId ruleId, String version) throws Exception {
        return admin().activate(ruleId, version);
    }

    public String swap(RuleId ruleId, String version) throws Exception {
        return hotSwap().swap(ruleId, version);
    }

    public String deactivate(RuleId ruleId) throws Exception {
        return hotSwap().deactivate(ruleId);
    }

    public String rollback(RuleId ruleId) throws Exception {
        return hotSwap().rollback(ruleId);
    }

    public List<Path> collectGarbage() throws Exception {
        return admin().collectGarbage();
    }

    private RulePackAdminService admin() {
        return services.admin()
                .orElseThrow(
                        () -> new IllegalStateException("Rule-pack administration is unavailable"));
    }

    private RulePackHotSwapService hotSwap() {
        return new RulePackHotSwapService(
                admin(),
                services.runtime()
                        .orElseThrow(
                                () -> new IllegalStateException("Rule-pack runtime is unavailable")),
                executors,
                services.resources()
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Rule resource verification is unavailable")),
                resources);
    }
}
