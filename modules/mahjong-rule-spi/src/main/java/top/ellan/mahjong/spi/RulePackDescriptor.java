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
    private static final int MAX_PROFILES = 64;
    private static final int MAX_REQUIRED_RESOURCES = 128;

    public RulePackDescriptor {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        spiVersion = Objects.requireNonNull(spiVersion, "spiVersion");
        requiredCoreVersion = Objects.requireNonNull(requiredCoreVersion, "requiredCoreVersion");
        if (version.length() > 64
                || spiVersion.length() > 32
                || requiredCoreVersion.length() > 128) {
            throw new IllegalArgumentException("Rule-pack version metadata is too large");
        }
        if (stateSchemaVersion < 1) {
            throw new IllegalArgumentException("State schema version must be positive");
        }
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(requiredResources, "requiredResources");
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("At least one profile is required");
        }
        if (profiles.size() > MAX_PROFILES || requiredResources.size() > MAX_REQUIRED_RESOURCES) {
            throw new IllegalArgumentException("Rule-pack descriptor exceeds bounded output limits");
        }
        if (requiredResources.stream().anyMatch(resource ->
                resource == null || resource.length() > 256)) {
            throw new IllegalArgumentException("Rule-pack resource name is too large");
        }
        profiles = List.copyOf(profiles);
        requiredResources = Set.copyOf(requiredResources);
    }
}
