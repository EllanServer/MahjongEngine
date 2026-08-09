package top.ellan.mahjong.application.lobby.command;

import java.util.Objects;
import top.ellan.mahjong.domain.lobby.TableLobby;

/** Pure reducer result. Rejections always retain the identical state instance. */
public record LobbyReduction(
        TableLobby state,
        boolean accepted,
        boolean changed,
        boolean startRequested,
        String reasonCode) {
    public LobbyReduction {
        Objects.requireNonNull(state, "state");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        if (!accepted && (changed || startRequested)) {
            throw new IllegalArgumentException("a rejected lobby command cannot have side effects");
        }
    }

    static LobbyReduction rejected(TableLobby state, String reasonCode) {
        return new LobbyReduction(state, false, false, false, reasonCode);
    }

    static LobbyReduction accepted(
            TableLobby state, boolean changed, boolean startRequested, String reasonCode) {
        return new LobbyReduction(state, true, changed, startRequested, reasonCode);
    }
}
