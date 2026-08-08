package top.ellan.mahjong.runtime;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import top.ellan.mahjong.spi.RuleId;

/** One artifact bound by a signed registry payload. */
public record RulePackRegistryEntry(
        RuleId ruleId,
        String version,
        URI artifactUri,
        String sha256,
        String spiVersion,
        String requiredCoreVersion,
        long sizeBytes) {
    public RulePackRegistryEntry {
        Objects.requireNonNull(ruleId, "ruleId");
        version = Objects.requireNonNull(version, "version");
        Objects.requireNonNull(artifactUri, "artifactUri");
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        spiVersion = Objects.requireNonNull(spiVersion, "spiVersion");
        requiredCoreVersion = Objects.requireNonNull(requiredCoreVersion, "requiredCoreVersion");
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
