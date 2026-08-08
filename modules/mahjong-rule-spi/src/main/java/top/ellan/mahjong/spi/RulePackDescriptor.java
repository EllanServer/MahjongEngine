package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Self-description inspected before a rule pack can be activated. */
public record RulePackDescriptor(
        RuleId ruleId,
        String version,
        String spiVersion,
        String requiredCoreVersion,
        int stateSchemaVersion,
        List<RuleProfileDescriptor> profiles,
        Set<String> requiredResources) {
    public RulePackDescriptor {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        spiVersion = Objects.requireNonNull(spiVersion, "spiVersion");
        requiredCoreVersion = Objects.requireNonNull(requiredCoreVersion, "requiredCoreVersion");
        if (stateSchemaVersion < 1) {
            throw new IllegalArgumentException("State schema version must be positive");
        }
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        requiredResources = Set.copyOf(Objects.requireNonNull(requiredResources, "requiredResources"));
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("At least one profile is required");
        }
    }
}
