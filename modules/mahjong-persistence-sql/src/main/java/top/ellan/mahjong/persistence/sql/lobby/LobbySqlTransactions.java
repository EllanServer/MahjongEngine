package top.ellan.mahjong.persistence.sql.lobby;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import top.ellan.mahjong.domain.TableId;

/** Cross-aggregate lobby operation used by atomic lobby-to-match activation. */
public final class LobbySqlTransactions {
    private LobbySqlTransactions() {}

    public static boolean delete(Connection connection, TableId tableId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tableId, "tableId");
        deleteMembers(connection, tableId);
        try (PreparedStatement delete =
                connection.prepareStatement(
                        "DELETE FROM table_lobby WHERE table_id = ?")) {
            delete.setString(1, tableId.toString());
            return delete.executeUpdate() == 1;
        }
    }

    static void deleteMembers(Connection connection, TableId tableId)
            throws SQLException {
        try (PreparedStatement delete =
                connection.prepareStatement(
                        "DELETE FROM table_lobby_spectator WHERE table_id = ?")) {
            delete.setString(1, tableId.toString());
            delete.executeUpdate();
        }
        try (PreparedStatement delete =
                connection.prepareStatement(
                        "DELETE FROM table_lobby_seat WHERE table_id = ?")) {
            delete.setString(1, tableId.toString());
            delete.executeUpdate();
        }
    }
}
