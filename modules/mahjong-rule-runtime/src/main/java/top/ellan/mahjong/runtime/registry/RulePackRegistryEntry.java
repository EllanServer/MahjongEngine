package top.ellan.mahjong.runtime.registry;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.spi.RuleId;

/** One rule JAR and its optional resource-only companion bound by a signed registry payload. */
public record RulePackRegistryEntry(
        RuleId ruleId,
        String version,
        URI artifactUri,
        String sha256,
        String spiVersion,
        String requiredCoreVersion,
        long sizeBytes,
        Optional<RuleResourcePackArtifact> resources) {
    public RulePackRegistryEntry {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        Objects.requireNonNull(artifactUri, "artifactUri");
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        spiVersion = Objects.requireNonNull(spiVersion, "spiVersion");
        requiredCoreVersion = Objects.requireNonNull(requiredCoreVersion, "requiredCoreVersion");
        resources = Objects.requireNonNull(resources, "resources");
        if (!OfficialRuleIds.ALL.contains(ruleId)) {
            throw new IllegalArgumentException("Registry contains a non-official rule id");
        }
        if (!version.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")) {
            throw new IllegalArgumentException("Invalid version");
        }
        if (!"https".equalsIgnoreCase(artifactUri.getScheme())) {
            throw new IllegalArgumentException("Rule artifacts must use HTTPS");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        if (sizeBytes < 1 || sizeBytes > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("Artifact size is outside the 64 MiB limit");
        }
    }
}
