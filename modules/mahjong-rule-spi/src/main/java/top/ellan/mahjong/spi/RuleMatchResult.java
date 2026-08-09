package top.ellan.mahjong.spi;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Complete terminal match result used by the core's history and ranking projections. */
public record RuleMatchResult(String rankSystem, List<RulePlayerResult> players) {
    private static final Pattern VALID_RANK_SYSTEM =
            Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public RuleMatchResult {
        rankSystem = Objects.requireNonNull(rankSystem, "rankSystem");
        List<RulePlayerResult> normalizedPlayers =
                List.copyOf(Objects.requireNonNull(players, "players"));
        players = normalizedPlayers;
        if (!VALID_RANK_SYSTEM.matcher(rankSystem).matches()) {
            throw new IllegalArgumentException("Invalid rank system: " + rankSystem);
        }
        if (players.size() < 2 || players.size() > 8) {
            throw new IllegalArgumentException("A result requires two to eight players");
        }
        if (new HashSet<>(players.stream().map(RulePlayerResult::playerId).toList()).size()
                        != players.size()
                || new HashSet<>(players.stream().map(RulePlayerResult::seatId).toList()).size()
                        != players.size()) {
            throw new IllegalArgumentException("Result players and seats must be unique");
        }
        if (normalizedPlayers.stream()
                .anyMatch(result -> result.placement() > normalizedPlayers.size())) {
            throw new IllegalArgumentException("Placement exceeds player count");
        }
    }
}
