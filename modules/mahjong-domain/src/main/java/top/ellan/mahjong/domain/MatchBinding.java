package top.ellan.mahjong.domain;

import java.time.Instant;
import java.util.Objects;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RulePackRef;

/** Rule-pack provenance pinned to a match. Rule packs are the only production mode. */
public record MatchBinding(
        MatchId matchId,
        RulePackRef rulePack,
        ProfileId profile,
        String configurationSha256,
        Instant createdAt) {
    public MatchBinding {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(rulePack, "rulePack");
        Objects.requireNonNull(profile, "profile");
        configurationSha256 = Objects.requireNonNull(configurationSha256, "configurationSha256");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!configurationSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid configuration SHA-256");
        }
    }
}
