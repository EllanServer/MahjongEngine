package top.ellan.mahjong.plugin.bootstrap.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.ellan.mahjong.persistence.sql.event.JdbcEventStore;
import top.ellan.mahjong.persistence.sql.match.JdbcMatchRepository;
import top.ellan.mahjong.persistence.sql.anchor.JdbcTableAnchorRepository;
import top.ellan.mahjong.persistence.sql.lobby.JdbcTableLobbyRepository;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;
import top.ellan.mahjong.plugin.config.PluginConfiguration;

/** Opens, migrates and probes SQL without leaking JDBC setup into the composition root. */
public final class DatabaseBootstrap {
    private final PluginConfiguration.Database configuration;
    private final Executor ioExecutor;
    private final Logger logger;

    public DatabaseBootstrap(
            PluginConfiguration.Database configuration,
            Executor ioExecutor,
            Logger logger) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public DatabaseRuntime initialize() {
        HikariDataSource dataSource = null;
        try {
            dataSource = new HikariDataSource(hikariConfiguration());
            SqlConnectionFactory connections = dataSource::getConnection;
            new SqlSchemaMigrator(connections).migrate();
            JdbcEventStore events = new JdbcEventStore(connections, ioExecutor);
            if (!events.probe()) {
                throw new SQLException("Database probe failed");
            }
            return new DatabaseRuntime(
                    Optional.of(dataSource),
                    Optional.of(new JdbcMatchRepository(connections)),
                    Optional.of(new JdbcTableAnchorRepository(connections)),
                    Optional.of(new JdbcTableLobbyRepository(connections)),
                    Optional.of(events));
        } catch (RuntimeException | SQLException failure) {
            if (dataSource != null) {
                dataSource.close();
            }
            logger.log(
                    Level.SEVERE,
                    "Database unavailable; matches cannot start or advance",
                    failure);
            return DatabaseRuntime.unavailable();
        }
    }

    private HikariConfig hikariConfiguration() {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("MahjongPaper-SQL");
        hikari.setJdbcUrl(configuration.jdbcUrl());
        hikari.setUsername(configuration.username());
        hikari.setPassword(configuration.password());
        hikari.setMaximumPoolSize(configuration.maximumPoolSize());
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(2_000L);
        hikari.setValidationTimeout(1_000L);
        hikari.setInitializationFailTimeout(-1L);
        return hikari;
    }
}
