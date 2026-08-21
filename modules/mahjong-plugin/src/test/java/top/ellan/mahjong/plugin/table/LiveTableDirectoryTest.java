package top.ellan.mahjong.plugin.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;

class LiveTableDirectoryTest {
    @Test
    void tableIdsIncludesAndReleasesReservations() {
        LiveTableDirectory directory = new LiveTableDirectory();
        TableId tableId = TableId.random();
        TableParticipant spectator = new TableParticipant(
                new PlayerId(UUID.randomUUID()), ParticipantRole.SPECTATOR, Optional.empty());

        assertTrue(directory.reserve(tableId, List.of(spectator)));
        assertEquals(Set.of(tableId), directory.tableIds());

        directory.releaseReservation(tableId);
        assertTrue(directory.tableIds().isEmpty());
    }
}
