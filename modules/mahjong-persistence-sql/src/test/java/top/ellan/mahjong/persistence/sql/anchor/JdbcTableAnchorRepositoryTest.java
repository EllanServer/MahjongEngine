package top.ellan.mahjong.persistence.sql.anchor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.persistence.sql.connection.SqlConnectionFactory;
import top.ellan.mahjong.persistence.sql.schema.SqlSchemaMigrator;

class JdbcTableAnchorRepositoryTest {
    private JdbcTableAnchorRepository anchors;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlConnectionFactory connections = () -> DriverManager.getConnection(url, "sa", "");
        new SqlSchemaMigrator(connections).migrate();
        anchors = new JdbcTableAnchorRepository(connections);
    }

    @Test
    void loadsOnlyAnchorsInTheRecoveryWorkset() throws Exception {
        TableAnchor first = anchor(TableId.random(), 1);
        TableAnchor second = anchor(TableId.random(), 2);
        TableAnchor unrelated = anchor(TableId.random(), 3);
        anchors.save(first);
        anchors.save(second);
        anchors.save(unrelated);

        Map<TableId, TableAnchor> found = anchors.findAll(
                List.of(first.tableId(), second.tableId(), first.tableId(), TableId.random()));

        assertEquals(Map.of(first.tableId(), first, second.tableId(), second), found);
    }

    @Test
    void emptyRecoveryWorksetDoesNotOpenTheDatabase() throws Exception {
        JdbcTableAnchorRepository disconnected =
                new JdbcTableAnchorRepository(() -> {
                    throw new AssertionError("empty lookup must not open a connection");
                });

        assertTrue(disconnected.findAll(List.of()).isEmpty());
    }

    private static TableAnchor anchor(TableId tableId, int offset) {
        return new TableAnchor(
                tableId,
                new UUID(0, offset).toString(),
                offset + 0.5,
                64 + offset,
                offset + 0.5,
                offset * 90.0F,
                0.0F);
    }
}
