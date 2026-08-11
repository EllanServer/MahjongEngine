package top.ellan.mahjong.application.lobby.runtime;

import java.util.Objects;
import top.ellan.mahjong.application.lobby.actor.LobbyTableActor;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.lobby.TableLobby;

/** Runtime handle for one pre-match table; values exposed by it are immutable snapshots. */
public record HostedLobby(TableAnchor anchor, LobbyTableActor actor) {
    public HostedLobby {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(actor, "actor");
        if (!anchor.tableId().equals(actor.state().tableId())) {
            throw new IllegalArgumentException("anchor and lobby actor table ids differ");
        }
    }

    public TableId tableId() {
        return anchor.tableId();
    }

    public TableLobby state() {
        return actor.state();
    }
}
