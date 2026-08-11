package top.ellan.mahjong.plugin.bootstrap.rules;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.runtime.admin.RulePackAdminService;
import top.ellan.mahjong.runtime.admin.RulePackInventoryReader;
import top.ellan.mahjong.runtime.lifecycle.RulePackRuntime;
import top.ellan.mahjong.runtime.resources.RuleResourcePackResolver;

/** Restart-scoped rule-pack runtime and administration services. */
public record RulePackRuntimeServices(
        Optional<RulePackRuntime> runtime,
        Optional<RulePackAdminService> admin,
        RulePackInventoryReader inventory,
        Optional<RuleResourcePackResolver> resources)
        implements AutoCloseable {
    public RulePackRuntimeServices {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(resources, "resources");
    }

    @Override
    public void close() {
        runtime.ifPresent(RulePackRuntimeServices::closeRuntime);
    }

    private static void closeRuntime(RulePackRuntime runtime) {
        try {
            runtime.close();
        } catch (IOException ignored) {
            // Runtime shutdown has no useful recovery action.
        }
    }
}
