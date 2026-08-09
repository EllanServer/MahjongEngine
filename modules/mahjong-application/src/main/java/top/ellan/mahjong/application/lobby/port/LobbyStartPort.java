package top.ellan.mahjong.application.lobby.port;

import top.ellan.mahjong.application.TableActionEndpoint;
import top.ellan.mahjong.domain.TableLobby;

/** Starts durable rule-state creation without blocking the lobby actor. */
@FunctionalInterface
public interface LobbyStartPort {
    void requestStart(TableLobby lobby, TableActionEndpoint expectedEndpoint);
}
