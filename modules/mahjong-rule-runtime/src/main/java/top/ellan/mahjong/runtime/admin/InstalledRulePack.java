package top.ellan.mahjong.runtime.admin;

import java.nio.file.Path;
import java.util.Objects;

import top.ellan.mahjong.spi.RuleId;

/** One artifact visible in the restart-scoped rule-pack inventory. */
public record InstalledRulePack(
        RuleId ruleId, String version, Path artifact, boolean active, boolean pending) {
    public InstalledRulePack {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(version, "version");
        artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
    }
}
