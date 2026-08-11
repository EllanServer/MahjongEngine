package top.ellan.mahjong.domain.lobby;

/** Core-owned pre-match lifecycle. Rule state is not created until {@link #STARTING}. */
public enum LobbyPhase {
    WAITING,
    STARTING,
    CLOSED
}
