package top.ellan.mahjong.application.lobby.port;

import top.ellan.mahjong.domain.lobby.TableLobby;

/** Non-blocking observer used for indexes and latest-only durable lobby writes. */
@FunctionalInterface
public interface LobbyStateObserver {
    LobbyStateObserver NOOP = ignored -> {};

    void changed(TableLobby state);
}
