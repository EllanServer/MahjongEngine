package top.ellan.mahjong.persistence.sql.anchor;

import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;

/** Durable table anchors. Calls are blocking and belong on the bounded I/O executor. */
public final class JdbcTableAnchorRepository {
    private static final int LOOKUP_BATCH_SIZE = 256;
    private final SqlConnectionFactory connections;

    public JdbcTableAnchorRepository(SqlConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public void save(TableAnchor anchor) throws SQLException {
        Objects.requireNonNull(anchor, "anchor");
        try (Connection connection = connections.open()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (exists(connection, anchor.tableId())) {
                    update(connection, anchor);
                } else {
                    insert(connection, anchor);
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

    public Optional<TableAnchor> find(TableId tableId) throws SQLException {
        Objects.requireNonNull(tableId, "tableId");
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT world_id, x, y, z, yaw, pitch FROM table_anchor WHERE table_id = ?")) {
            statement.setString(1, tableId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(tableId, result)) : Optional.empty();
            }
        }
    }

    /** Loads anchors only for the supplied recovery workset, in bounded SQL batches. */
    public Map<TableId, TableAnchor> findAll(Collection<TableId> tableIds) throws SQLException {
        Objects.requireNonNull(tableIds, "tableIds");
        LinkedHashSet<TableId> distinct = new LinkedHashSet<>();
        tableIds.forEach(tableId -> distinct.add(Objects.requireNonNull(tableId, "tableId")));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<TableId> ids = List.copyOf(distinct);
        HashMap<TableId, TableAnchor> anchors = new HashMap<>();
        try (Connection connection = connections.open()) {
            for (int start = 0; start < ids.size(); start += LOOKUP_BATCH_SIZE) {
                List<TableId> batch =
                        ids.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, ids.size()));
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                try (PreparedStatement statement = connection.prepareStatement(
                                "SELECT table_id, world_id, x, y, z, yaw, pitch "
                                        + "FROM table_anchor WHERE table_id IN ("
                                        + placeholders
                                        + ")")) {
                    for (int index = 0; index < batch.size(); index++) {
                        statement.setString(index + 1, batch.get(index).toString());
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            TableId tableId = TableId.parse(result.getString("table_id"));
                            anchors.put(tableId, read(tableId, result));
                        }
                    }
                }
            }
        }
        return Map.copyOf(anchors);
    }

    public void delete(TableId tableId) throws SQLException {
        Objects.requireNonNull(tableId, "tableId");
        try (Connection connection = connections.open();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM table_anchor WHERE table_id = ?")) {
            statement.setString(1, tableId.toString());
            statement.executeUpdate();
        }
    }

    private static boolean exists(Connection connection, TableId tableId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT 1 FROM table_anchor WHERE table_id = ?")) {
            statement.setString(1, tableId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void insert(Connection connection, TableAnchor anchor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO table_anchor (table_id, world_id, x, y, z, yaw, pitch) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            bind(statement, anchor);
            statement.executeUpdate();
        }
    }

    private static void update(Connection connection, TableAnchor anchor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE table_anchor SET world_id = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ? "
                        + "WHERE table_id = ?")) {
            statement.setString(1, anchor.worldId());
            statement.setDouble(2, anchor.x());
            statement.setDouble(3, anchor.y());
            statement.setDouble(4, anchor.z());
            statement.setFloat(5, anchor.yaw());
            statement.setFloat(6, anchor.pitch());
            statement.setString(7, anchor.tableId().toString());
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, TableAnchor anchor)
            throws SQLException {
        statement.setString(1, anchor.tableId().toString());
        statement.setString(2, anchor.worldId());
        statement.setDouble(3, anchor.x());
        statement.setDouble(4, anchor.y());
        statement.setDouble(5, anchor.z());
        statement.setFloat(6, anchor.yaw());
        statement.setFloat(7, anchor.pitch());
    }

    private static TableAnchor read(TableId tableId, ResultSet result) throws SQLException {
        return new TableAnchor(
                tableId,
                result.getString("world_id"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getFloat("yaw"),
                result.getFloat("pitch"));
    }
}
