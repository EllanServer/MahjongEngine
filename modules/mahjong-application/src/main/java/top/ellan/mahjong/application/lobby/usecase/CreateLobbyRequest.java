package top.ellan.mahjong.application.lobby.usecase;

import java.util.Map;
import java.util.Objects;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

/** Immutable input captured by a platform adapter before asynchronous lobby creation. */
public record CreateLobbyRequest(
        TableAnchor anchor,
        PlayerId ownerId,
        RuleId ruleId,
        ProfileId profileId,
        Map<String, String> configuration,
        int seatCount) {
    public CreateLobbyRequest {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(profileId, "profileId");
        configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
        if (seatCount < 2 || seatCount > 4) {
            throw new IllegalArgumentException("seatCount must be between two and four");
        }
    }
}
