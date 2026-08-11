package top.ellan.mahjong.platform.paper.region;

import top.ellan.mahjong.domain.table.TableId;

/** Resolves the immutable Folia ownership region of one table. */
@FunctionalInterface
public interface TableRegionResolver {
    RegionKey regionFor(TableId tableId);
}
