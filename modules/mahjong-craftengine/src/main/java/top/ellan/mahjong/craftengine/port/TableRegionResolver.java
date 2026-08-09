package top.ellan.mahjong.craftengine.port;

import top.ellan.mahjong.domain.table.TableId;

/** Resolves the immutable anchor region of a table. */
@FunctionalInterface
public interface TableRegionResolver {
    RegionKey regionFor(TableId tableId);
}
