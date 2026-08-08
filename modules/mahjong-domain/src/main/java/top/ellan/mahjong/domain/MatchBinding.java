package top.ellan.mahjong.domain;

import java.time.Instant;
import java.util.Objects;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RulePackRef;

/** Rule-pack provenance and migration mode pinned to a match. */
public record MatchBinding(
        MatchId matchId,
        RulePackRef rulePack,
        ProfileId profile,
        RuleMigrationMode migrationMode,
        String configurationSha256,
        Instant createdAt) {
    public MatchBinding {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(rulePack, "rulePack");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(migrationMode, "migrationMode");
        configurationSha256 = Objects.requireNonNull(configurationSha256, "configurationSha256");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!configurationSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid configuration SHA-256");
        }
    }
}
