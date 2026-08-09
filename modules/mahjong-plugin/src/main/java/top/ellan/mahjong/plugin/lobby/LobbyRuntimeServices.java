package top.ellan.mahjong.plugin.lobby;

import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.anchor.JdbcTableAnchorRepository;
import top.ellan.mahjong.plugin.RulePackMatchCoordinator;

/** Restart-scoped services bound after SQL and signed rule packs finish initialization. */
public record LobbyRuntimeServices(
        Optional<LobbyRepositoryPort> lobbies,
        Optional<JdbcTableAnchorRepository> anchors,
        Optional<JdbcMatchRepository> matches,
        Optional<RulePackMatchCoordinator> matchCoordinator) {
    public LobbyRuntimeServices {
        lobbies = Objects.requireNonNull(lobbies, "lobbies");
        anchors = Objects.requireNonNull(anchors, "anchors");
        matches = Objects.requireNonNull(matches, "matches");
        matchCoordinator = Objects.requireNonNull(matchCoordinator, "matchCoordinator");
    }
}
