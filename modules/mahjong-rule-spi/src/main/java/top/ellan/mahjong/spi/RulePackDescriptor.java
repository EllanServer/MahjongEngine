package top.ellan.mahjong.spi;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Self-description inspected before a rule pack can be activated.
 *
 * @param ruleId stable identifier of the rule family
 * @param version provider version of this rule pack
 * @param spiVersion rule SPI version required by the pack
 * @param requiredCoreVersion core-version constraint declared by the pack
 * @param stateSchemaVersion version of the provider-owned state schema
 * @param profiles rule profiles offered by the pack
 * @param requiredResources logical resource bundles required by the pack
 */
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

    /**
     * Creates a validated rule-pack self-description.
     *
     * @param ruleId stable identifier of the rule family
     * @param version provider version of this rule pack
     * @param spiVersion rule SPI version required by the pack
     * @param requiredCoreVersion core-version constraint declared by the pack
     * @param stateSchemaVersion version of the provider-owned state schema
     * @param profiles rule profiles offered by the pack
     * @param requiredResources logical resource bundles required by the pack
     */
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
