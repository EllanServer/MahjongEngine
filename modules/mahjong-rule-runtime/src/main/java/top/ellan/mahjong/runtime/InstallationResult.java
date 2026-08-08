package top.ellan.mahjong.runtime;

import java.nio.file.Path;
import java.util.Objects;
import top.ellan.mahjong.spi.RulePackRef;

/** Completed installation; activation still requires a restart. */
public record InstallationResult(
        RulePackRef reference, Path artifact, RulePackRegistryEntry registryEntry, boolean alreadyInstalled) {
    public InstallationResult {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(registryEntry, "registryEntry");
    }
}
