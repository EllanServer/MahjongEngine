package top.ellan.mahjong.spi;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic input for creating a complete match. */
public record MatchSetup(
        ProfileId profileId,
        MatchSeed seed,
        List<MatchPlayer> players,
        Map<String, String> configuration) {
    public MatchSetup {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(seed, "seed");
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
        if (players.size() < 2 || players.size() > 4) {
            throw new IllegalArgumentException("A match requires two to four players");
        }
        if (new HashSet<>(players.stream().map(MatchPlayer::playerId).toList()).size() != players.size()
                || new HashSet<>(players.stream().map(MatchPlayer::seatId).toList()).size() != players.size()) {
            throw new IllegalArgumentException("Player and seat assignments must be unique");
        }
    }
}
