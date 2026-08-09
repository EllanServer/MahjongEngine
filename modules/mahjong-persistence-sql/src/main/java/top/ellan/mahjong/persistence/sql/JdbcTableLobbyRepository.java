package top.ellan.mahjong.persistence.sql;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.application.lobby.port.LobbyRepositoryPort;
import top.ellan.mahjong.domain.LobbyPhase;
import top.ellan.mahjong.domain.LobbySeat;
import top.ellan.mahjong.domain.SeatPresence;
import top.ellan.mahjong.domain.TableAnchor;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableLobby;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Durable latest-state storage for pre-match lobbies. Calls belong on the bounded I/O pool. */
public final class JdbcTableLobbyRepository implements LobbyRepositoryPort {
    private static final int MAX_CONFIGURATION_ENTRIES = 64;

    private final SqlConnectionFactory connections;

    public JdbcTableLobbyRepository(SqlConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Creates the world anchor and initial lobby atomically. */
    @Override
    public void create(TableLobby lobby, TableAnchor anchor) throws SQLException {
        Objects.requireNonNull(lobby, "lobby");
        Objects.requireNonNull(anchor, "anchor");
        if (!lobby.tableId().equals(anchor.tableId())) {
            throw new IllegalArgumentException("lobby and anchor table ids differ");
        }
        try (Connection connection = connections.open()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (findHeader(connection, lobby.tableId()).isPresent()) {
                    throw new PersistenceConflictException("lobby already exists");
                }
                insertOrVerifyAnchor(connection, anchor);
                insertHeader(connection, lobby, lobby.createdAt());
                replaceMembers(connection, lobby);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    /** Replaces one tiny lobby snapshot in a transaction; stale revisions cannot overwrite newer. */
    @Override
    public void save(TableLobby lobby, Instant updatedAt) throws SQLException {
        Objects.requireNonNull(lobby, "lobby");
        Objects.requireNonNull(updatedAt, "updatedAt");
        try (Connection connection = connections.open()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Header existing = findHeader(connection, lobby.tableId())
                        .orElseThrow(() -> new PersistenceConflictException("lobby does not exist"));
                if (existing.revision() >= lobby.revision()) {
                    connection.rollback();
                    return;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE table_lobby SET owner_id = ?, rule_id = ?, profile_id = ?, "
                                + "configuration_payload = ?, seat_count = ?, revision = ?, "
                                + "phase = ?, updated_at = ? WHERE table_id = ? AND revision < ?")) {
                    bindHeader(update, lobby, updatedAt, false);
                    update.setLong(10, lobby.revision());
                    if (update.executeUpdate() != 1) {
                        throw new PersistenceConflictException("lobby disappeared during save");
                    }
                }
                replaceMembers(connection, lobby);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public List<TableLobby> list() throws SQLException {
        ArrayList<TableLobby> result = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement select = connection.prepareStatement(
                        "SELECT table_id, owner_id, rule_id, profile_id, configuration_payload, "
                                + "seat_count, revision, phase, created_at FROM table_lobby "
                                + "ORDER BY created_at, table_id");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                Header header = readHeader(rows);
                result.add(readLobby(connection, header).recoveredOffline());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<TableLobby> find(TableId tableId) throws SQLException {
        Objects.requireNonNull(tableId, "tableId");
        try (Connection connection = connections.open()) {
            Optional<Header> header = findHeader(connection, tableId);
            return header.isEmpty()
                    ? Optional.empty()
                    : Optional.of(readLobby(connection, header.orElseThrow()).recoveredOffline());
        }
    }

    @Override
    public void delete(TableId tableId) throws SQLException {
        Objects.requireNonNull(tableId, "tableId");
        try (Connection connection = connections.open()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                deleteMembers(connection, tableId);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM table_lobby WHERE table_id = ?")) {
                    delete.setString(1, tableId.toString());
                    delete.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    static boolean deleteWithin(Connection connection, TableId tableId) throws SQLException {
        deleteMembers(connection, tableId);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM table_lobby WHERE table_id = ?")) {
            delete.setString(1, tableId.toString());
            return delete.executeUpdate() == 1;
        }
    }

    private static TableLobby readLobby(Connection connection, Header header) throws SQLException {
        ArrayList<LobbySeat> seats = new ArrayList<>(header.seatCount());
        for (int index = 0; index < header.seatCount(); index++) {
            seats.add(LobbySeat.empty(new SeatId(index)));
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT seat_index, player_id, ready FROM table_lobby_seat "
                        + "WHERE table_id = ? ORDER BY seat_index")) {
            select.setString(1, header.tableId().toString());
            try (ResultSet rows = select.executeQuery()) {
                int seen = 0;
                while (rows.next()) {
                    int seatIndex = rows.getInt("seat_index");
                    if (seatIndex != seen++ || seatIndex >= seats.size()) {
                        throw new PersistenceConflictException("lobby seats are not contiguous");
                    }
                    String player = rows.getString("player_id");
                    boolean ready = rows.getBoolean("ready");
                    seats.set(
                            seatIndex,
                            player == null
                                    ? LobbySeat.empty(new SeatId(seatIndex))
                                    : new LobbySeat(
                                            new SeatId(seatIndex),
                                            Optional.of(PlayerId.parse(player)),
                                            ready,
                                            SeatPresence.ONLINE));
                }
                if (seen != seats.size()) {
                    throw new PersistenceConflictException("lobby is missing seat rows");
                }
            }
        }
        LinkedHashSet<PlayerId> spectators = new LinkedHashSet<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT player_id FROM table_lobby_spectator WHERE table_id = ? ORDER BY player_id")) {
            select.setString(1, header.tableId().toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    spectators.add(PlayerId.parse(rows.getString("player_id")));
                }
            }
        }
        return new TableLobby(
                header.tableId(),
                header.revision(),
                header.ownerId(),
                header.ruleId(),
                header.profileId(),
                header.configuration(),
                seats,
                spectators,
                header.phase(),
                header.createdAt());
    }

    private static Optional<Header> findHeader(Connection connection, TableId tableId)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT table_id, owner_id, rule_id, profile_id, configuration_payload, "
                        + "seat_count, revision, phase, created_at FROM table_lobby WHERE table_id = ?")) {
            select.setString(1, tableId.toString());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(readHeader(rows)) : Optional.empty();
            }
        }
    }

    private static Header readHeader(ResultSet row) throws SQLException {
        int seatCount = row.getInt("seat_count");
        if (seatCount < 2 || seatCount > 4) {
            throw new PersistenceConflictException("persisted lobby seat count is invalid");
        }
        return new Header(
                TableId.parse(row.getString("table_id")),
                PlayerId.parse(row.getString("owner_id")),
                new RuleId(row.getString("rule_id")),
                new ProfileId(row.getString("profile_id")),
                decodeConfiguration(row.getBytes("configuration_payload")),
                seatCount,
                row.getLong("revision"),
                LobbyPhase.valueOf(row.getString("phase")),
                row.getTimestamp("created_at").toInstant());
    }

    private static void insertHeader(Connection connection, TableLobby lobby, Instant updatedAt)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO table_lobby (owner_id, rule_id, profile_id, configuration_payload, "
                        + "seat_count, revision, phase, updated_at, table_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindHeader(insert, lobby, updatedAt, true);
            insert.executeUpdate();
        }
    }

    private static void bindHeader(
            PreparedStatement statement,
            TableLobby lobby,
            Instant updatedAt,
            boolean includeCreatedAt)
            throws SQLException {
        statement.setString(1, lobby.ownerId().toString());
        statement.setString(2, lobby.ruleId().value());
        statement.setString(3, lobby.profileId().value());
        statement.setBytes(4, encodeConfiguration(lobby.configuration()));
        statement.setInt(5, lobby.seats().size());
        statement.setLong(6, lobby.revision());
        statement.setString(7, lobby.phase().name());
        statement.setTimestamp(8, Timestamp.from(updatedAt));
        statement.setString(9, lobby.tableId().toString());
        if (includeCreatedAt) {
            statement.setTimestamp(10, Timestamp.from(lobby.createdAt()));
        }
    }

    private static void replaceMembers(Connection connection, TableLobby lobby)
            throws SQLException {
        deleteMembers(connection, lobby.tableId());
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO table_lobby_seat (table_id, seat_index, player_id, ready) "
                        + "VALUES (?, ?, ?, ?)")) {
            for (LobbySeat seat : lobby.seats()) {
                insert.setString(1, lobby.tableId().toString());
                insert.setInt(2, seat.seatId().value());
                if (seat.occupant().isPresent()) {
                    insert.setString(3, seat.occupant().orElseThrow().toString());
                } else {
                    insert.setNull(3, java.sql.Types.VARCHAR);
                }
                insert.setBoolean(4, seat.ready());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        if (!lobby.spectators().isEmpty()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO table_lobby_spectator (table_id, player_id) VALUES (?, ?)")) {
                for (PlayerId spectator : lobby.spectators().stream().sorted().toList()) {
                    insert.setString(1, lobby.tableId().toString());
                    insert.setString(2, spectator.toString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private static void deleteMembers(Connection connection, TableId tableId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM table_lobby_spectator WHERE table_id = ?")) {
            delete.setString(1, tableId.toString());
            delete.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM table_lobby_seat WHERE table_id = ?")) {
            delete.setString(1, tableId.toString());
            delete.executeUpdate();
        }
    }

    private static void insertOrVerifyAnchor(Connection connection, TableAnchor anchor)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT world_id, x, y, z, yaw, pitch FROM table_anchor WHERE table_id = ?")) {
            select.setString(1, anchor.tableId().toString());
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    TableAnchor existing = new TableAnchor(
                            anchor.tableId(),
                            row.getString("world_id"),
                            row.getDouble("x"),
                            row.getDouble("y"),
                            row.getDouble("z"),
                            row.getFloat("yaw"),
                            row.getFloat("pitch"));
                    if (!existing.equals(anchor)) {
                        throw new PersistenceConflictException(
                                "table id belongs to a different anchor");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO table_anchor (table_id, world_id, x, y, z, yaw, pitch) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, anchor.tableId().toString());
            insert.setString(2, anchor.worldId());
            insert.setDouble(3, anchor.x());
            insert.setDouble(4, anchor.y());
            insert.setDouble(5, anchor.z());
            insert.setFloat(6, anchor.yaw());
            insert.setFloat(7, anchor.pitch());
            insert.executeUpdate();
        }
    }

    private static byte[] encodeConfiguration(Map<String, String> configuration) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(configuration.size());
                configuration.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(
                                entry -> {
                                    try {
                                        output.writeUTF(entry.getKey());
                                        output.writeUTF(entry.getValue());
                                    } catch (IOException impossible) {
                                        throw new ConfigurationEncodingException(impossible);
                                    }
                                });
            }
            return bytes.toByteArray();
        } catch (IOException | ConfigurationEncodingException failure) {
            throw new IllegalStateException("could not encode lobby configuration", failure);
        }
    }

    private static Map<String, String> decodeConfiguration(byte[] payload)
            throws PersistenceConflictException {
        if (payload == null || payload.length > 512 * 1024) {
            throw new PersistenceConflictException("invalid lobby configuration payload");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int size = input.readInt();
            if (size < 0 || size > MAX_CONFIGURATION_ENTRIES) {
                throw new PersistenceConflictException("too many lobby configuration entries");
            }
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (int index = 0; index < size; index++) {
                String key = input.readUTF();
                String value = input.readUTF();
                if (result.putIfAbsent(key, value) != null) {
                    throw new PersistenceConflictException("duplicate lobby configuration key");
                }
            }
            if (input.read() != -1) {
                throw new PersistenceConflictException("trailing lobby configuration bytes");
            }
            return Map.copyOf(result);
        } catch (EOFException truncated) {
            throw new PersistenceConflictException("truncated lobby configuration payload");
        } catch (IOException invalid) {
            throw new PersistenceConflictException("invalid lobby configuration payload");
        }
    }

    private record Header(
            TableId tableId,
            PlayerId ownerId,
            RuleId ruleId,
            ProfileId profileId,
            Map<String, String> configuration,
            int seatCount,
            long revision,
            LobbyPhase phase,
            Instant createdAt) {}

    private static final class ConfigurationEncodingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ConfigurationEncodingException(IOException cause) {
            super(cause);
        }
    }
}
