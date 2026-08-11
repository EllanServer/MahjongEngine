package top.ellan.mahjong.runtime.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import top.ellan.mahjong.runtime.catalog.OfficialRuleIds;
import top.ellan.mahjong.spi.RuleId;

/** Canonical on-disk layout rooted under plugins/MahjongPaper/rules. */
public final class RulePackPaths {
    private final Path root;

    public RulePackPaths(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public void createLayout() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(staging());
        Files.createDirectories(quarantine());
        for (RuleId ruleId : OfficialRuleIds.ALL) {
            Files.createDirectories(root.resolve(ruleId.value()));
        }
    }

    public Path root() {
        return root;
    }

    public Path staging() {
        return root.resolve("staging");
    }

    public Path quarantine() {
        return root.resolve("quarantine");
    }

    public Path registryCache() {
        return root.resolve("registry-cache.json");
    }

    public Path activationState() {
        return root.resolve("activation-state.json");
    }

    public Path versionDirectory(RuleId ruleId, String version) {
        requireOfficial(ruleId);
        requireSafeSegment(version, "version");
        return requireInsideRoot(root.resolve(ruleId.value()).resolve(version));
    }

    public Path installedJar(RuleId ruleId, String version) {
        return versionDirectory(ruleId, version).resolve(ruleId.value() + "-rule-pack.jar");
    }

    public Path installedResources(RuleId ruleId, String version) {
        return versionDirectory(ruleId, version)
                .resolve(ruleId.value() + "-resource-pack.zip");
    }

    public Path requireInsideRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes rule-pack root: " + candidate);
        }
        return normalized;
    }

    private static void requireOfficial(RuleId ruleId) {
        if (!OfficialRuleIds.ALL.contains(Objects.requireNonNull(ruleId, "ruleId"))) {
            throw new IllegalArgumentException("Unsupported rule id: " + ruleId);
        }
    }

    private static void requireSafeSegment(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")) {
            throw new IllegalArgumentException("Unsafe " + name + ": " + value);
        }
    }
}
