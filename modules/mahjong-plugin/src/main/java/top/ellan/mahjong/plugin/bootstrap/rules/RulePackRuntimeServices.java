package top.ellan.mahjong.plugin.bootstrap.rules;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import top.ellan.mahjong.runtime.admin.RulePackAdminService;
import top.ellan.mahjong.runtime.admin.RulePackInventoryReader;
import top.ellan.mahjong.runtime.lifecycle.RulePackRuntime;
import top.ellan.mahjong.runtime.resources.RuleResourcePackResolver;
import top.ellan.mahjong.spi.RulePackDescriptor;

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

    public List<RulePackDescriptor> activeDescriptors() {
        return runtime.map(current -> current.status().loadedActive().keySet().stream()
                        .sorted()
                        .map(current::providerForNewMatch)
                        .flatMap(Optional::stream)
                        .map(provider -> provider.descriptor())
                        .toList())
                .orElse(List.of());
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
