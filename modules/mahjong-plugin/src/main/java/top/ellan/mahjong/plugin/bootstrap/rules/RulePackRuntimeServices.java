package top.ellan.mahjong.plugin.bootstrap.rules;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.runtime.RulePackAdminService;
import top.ellan.mahjong.runtime.RulePackInventoryReader;
import top.ellan.mahjong.runtime.RulePackRuntime;

/** Restart-scoped rule-pack runtime and administration services. */
public record RulePackRuntimeServices(
        Optional<RulePackRuntime> runtime,
        Optional<RulePackAdminService> admin,
        RulePackInventoryReader inventory)
        implements AutoCloseable {
    public RulePackRuntimeServices {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(inventory, "inventory");
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
