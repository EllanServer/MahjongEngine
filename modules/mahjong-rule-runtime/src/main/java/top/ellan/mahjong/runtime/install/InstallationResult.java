package top.ellan.mahjong.runtime.install;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.runtime.registry.RulePackRegistryEntry;
import top.ellan.mahjong.spi.RulePackRef;

/** Completed code/resource installation; activation is a separate administrative operation. */
public record InstallationResult(
        RulePackRef reference,
        Path artifact,
        Optional<Path> resourceArtifact,
        RulePackRegistryEntry registryEntry,
        boolean alreadyInstalled) {
    public InstallationResult {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(artifact, "artifact");
        resourceArtifact = Objects.requireNonNull(resourceArtifact, "resourceArtifact")
                .map(path -> path.toAbsolutePath().normalize());
        Objects.requireNonNull(registryEntry, "registryEntry");
    }
}
