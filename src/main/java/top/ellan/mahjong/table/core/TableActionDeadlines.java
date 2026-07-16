package top.ellan.mahjong.table.core;

import java.util.Objects;
import java.util.UUID;

/** Narrow cross-package access to the action clock owned by a table session. */
public final class TableActionDeadlines {
    private TableActionDeadlines() {
    }

    public static void suspend(MahjongTableSession session, UUID playerId) {
        coordinator(session).suspend(playerId);
    }

    public static void resume(MahjongTableSession session, UUID playerId) {
        coordinator(session).resume(playerId);
    }

    public static void discardSuspension(MahjongTableSession session, UUID playerId) {
        coordinator(session).discardSuspension(playerId);
    }

    private static SessionActionDeadlineCoordinator coordinator(MahjongTableSession session) {
        return Objects.requireNonNull(session, "session").actionDeadlineCoordinator;
    }
}
