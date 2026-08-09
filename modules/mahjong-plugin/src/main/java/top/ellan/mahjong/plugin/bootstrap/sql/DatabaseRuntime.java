package top.ellan.mahjong.plugin.bootstrap.sql;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.persistence.sql.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.JdbcTableAnchorRepository;

/** Restart-scoped SQL resources exposed to the plugin composition layer. */
public record DatabaseRuntime(
        Optional<HikariDataSource> dataSource,
        Optional<JdbcMatchRepository> matches,
        Optional<JdbcTableAnchorRepository> anchors,
        Optional<LobbyRepositoryPort> lobbies,
        Optional<JdbcEventStore> events)
        implements AutoCloseable {
    public DatabaseRuntime {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(matches, "matches");
        Objects.requireNonNull(anchors, "anchors");
        Objects.requireNonNull(lobbies, "lobbies");
        Objects.requireNonNull(events, "events");
    }

    public static DatabaseRuntime unavailable() {
        return new DatabaseRuntime(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public boolean supportsMatches() {
        return matches.isPresent() && anchors.isPresent() && events.isPresent();
    }

    @Override
    public void close() {
        dataSource.ifPresent(HikariDataSource::close);
    }
}
