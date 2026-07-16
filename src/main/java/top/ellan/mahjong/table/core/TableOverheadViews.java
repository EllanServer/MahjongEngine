package top.ellan.mahjong.table.core;

import java.util.UUID;

/** Read and lifecycle controls for client-side overhead table views. */
public interface TableOverheadViews {
    boolean isActive(UUID playerId);

    boolean isAvailable();

    void closeAll();

    void closeTable(String tableId);
}
