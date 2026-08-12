package top.ellan.mahjong.application.lobby.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.spi.PlayerId;

/** Blocking persistence boundary; callers must invoke it only on the bounded I/O executor. */
public interface LobbyRepositoryPort {
    void create(TableLobby lobby, TableAnchor anchor) throws Exception;

    void save(TableLobby lobby, Instant updatedAt) throws Exception;

    List<TableLobby> list() throws Exception;

    Optional<TableLobby> find(TableId tableId) throws Exception;

    /**
     * Resets one completed table for reuse on the blocking persistence boundary.
     * Implementations should override this to perform the read/transform/write in one transaction.
     */
    default Optional<TableLobby> resetForReuse(
            TableId tableId, Set<PlayerId> departedPlayers, Instant updatedAt) throws Exception {
        Optional<TableLobby> stored = find(tableId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Optional<TableLobby> reusable = stored.orElseThrow().resetForReuse(departedPlayers);
        if (reusable.isPresent()) {
            save(reusable.orElseThrow(), updatedAt);
        }
        return reusable;
    }

    void delete(TableId tableId) throws Exception;
}
