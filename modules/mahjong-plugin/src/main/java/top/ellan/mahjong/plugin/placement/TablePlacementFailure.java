package top.ellan.mahjong.plugin.placement;

import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.domain.table.TableId;

/** Structured, localizable result from the bounded table-footprint validator. */
public record TablePlacementFailure(
        Reason reason, Optional<TableId> conflictingTable, Integer x, Integer y, Integer z) {
    public TablePlacementFailure {
        Objects.requireNonNull(reason, "reason");
        conflictingTable = Objects.requireNonNull(conflictingTable, "conflictingTable");
    }

    public static TablePlacementFailure simple(Reason reason) {
        return new TablePlacementFailure(reason, Optional.empty(), null, null, null);
    }

    public static TablePlacementFailure overlap(TableId tableId) {
        return new TablePlacementFailure(
                Reason.TOO_CLOSE_TO_TABLE,
                Optional.of(Objects.requireNonNull(tableId, "tableId")),
                null,
                null,
                null);
    }

    public static TablePlacementFailure blocked(int x, int y, int z) {
        return new TablePlacementFailure(
                Reason.BLOCKED_SPACE, Optional.empty(), x, y, z);
    }

    public enum Reason {
        INVALID_LOCATION,
        TOO_CLOSE_TO_TABLE,
        BLOCKED_SPACE,
        NOT_ENOUGH_HEIGHT,
        NOT_IN_GAME_ROOM,
        PROTECTED_AREA
    }
}
