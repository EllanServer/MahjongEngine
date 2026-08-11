package top.ellan.mahjong.runtime.loading;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipFile;

import top.ellan.mahjong.runtime.common.RulePackException;
import top.ellan.mahjong.runtime.security.Hashing;

/**
 * Content-authenticated JAR inspection with a bounded validation cache.
 *
 * <p>Verifying, activating and starting the same coordinate previously re-hashed the whole file and
 * re-walked every archive entry each time, and the pinned path additionally opened the archive twice
 * per load. Inspection is deterministic for a given file, so the validated result is cached under
 * its absolute path and SHA-256 digest. Filesystem timestamps and sizes are deliberately not trusted:
 * a same-size replacement can preserve both, especially on filesystems with coarse timestamp
 * precision.</p>
 *
 * <p>The SHA-256 is computed on every lookup. The cache removes the repeated archive walk for
 * unchanged bytes without ever allowing metadata collisions to bypass verification of new bytes.</p>
 */
final class ArtifactInspections {
    private static final int CAPACITY = 64;

    /** Verified facts about one archive. Reaching this record means the archive passed validation. */
    record Inspection(String sha256, RulePackManifest manifest) {
        Inspection {
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    private record Identity(Path path, String sha256) {
        private Identity {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private final Map<Identity, Inspection> cache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Identity, Inspection> eldest) {
                    return size() > CAPACITY;
                }
            };

    /**
     * Hashes, reads the manifest and validates the archive in a single pass.
     *
     * @param validator applied to the open archive; it must reject anything unsafe
     */
    synchronized Inspection inspect(Path artifact, ArchiveValidator validator)
            throws IOException, RulePackException {
        Path normalized = artifact.toAbsolutePath().normalize();
        String sha256 = Hashing.sha256(normalized);
        Identity identity = new Identity(normalized, sha256);
        Inspection cached = cache.get(identity);
        if (cached != null) {
            return cached;
        }
        RulePackManifest manifest;
        try (ZipFile jar = new ZipFile(normalized.toFile())) {
            manifest = RulePackManifest.read(jar);
            validator.validate(jar, manifest);
        }
        Inspection inspection = new Inspection(sha256, manifest);
        // A verdict and digest from different byte sequences must never escape this boundary.
        if (!Hashing.sha256(normalized).equals(sha256)) {
            throw new RulePackException("Rule-pack artifact changed during inspection");
        }
        cache.put(identity, inspection);
        return inspection;
    }

    @FunctionalInterface
    interface ArchiveValidator {
        void validate(ZipFile jar, RulePackManifest manifest) throws RulePackException;
    }
}
