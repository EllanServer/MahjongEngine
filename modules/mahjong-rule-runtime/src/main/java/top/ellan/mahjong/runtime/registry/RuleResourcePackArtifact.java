package top.ellan.mahjong.runtime.registry;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Resource-only companion artifact bound to the same signed coordinate as a rule JAR. */
public record RuleResourcePackArtifact(URI uri, String sha256, long sizeBytes) {
    public RuleResourcePackArtifact {
        uri = Objects.requireNonNull(uri, "uri");
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Rule resources must use HTTPS");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid resource SHA-256");
        }
        if (sizeBytes < 1 || sizeBytes > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("Resource size is outside the 64 MiB limit");
        }
    }
}
