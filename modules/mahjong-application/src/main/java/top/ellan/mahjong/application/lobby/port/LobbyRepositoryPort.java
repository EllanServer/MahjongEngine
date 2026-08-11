package top.ellan.mahjong.application.lobby.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.lobby.TableLobby;

/** Blocking persistence boundary; callers must invoke it only on the bounded I/O executor. */
public interface LobbyRepositoryPort {
    void create(TableLobby lobby, TableAnchor anchor) throws Exception;

    void save(TableLobby lobby, Instant updatedAt) throws Exception;

    List<TableLobby> list() throws Exception;

    Optional<TableLobby> find(TableId tableId) throws Exception;

    void delete(TableId tableId) throws Exception;
}
