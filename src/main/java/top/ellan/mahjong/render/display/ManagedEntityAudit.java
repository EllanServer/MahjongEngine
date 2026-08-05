package top.ellan.mahjong.render.display;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Scans the live world for entities tagged by {@link DisplayEntities} and reports or cleans
 * up orphans. An orphan is a managed entity whose {@code table_id} tag does not match any
 * active table id. Only entities carrying the {@code mahjong:managed_entity} tag are ever
 * removed by {@link #cleanupOrphans} — generic Interaction/ItemDisplay entities belonging to
 * other plugins (furniture, NPCs, decorations) are never touched.
 */
public final class ManagedEntityAudit {

    public record EntityReport(int total, Map<String, Integer> perTable, List<String> orphans) {
        public int orphanCount() {
            return this.orphans.size();
        }
    }

    private ManagedEntityAudit() {
    }

    /** Counts managed entities per table id and collects orphaned entity ids. */
    public static EntityReport scan(Plugin plugin, Set<String> activeTableIds) {
        Map<String, Integer> perTable = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!DisplayEntities.isManagedEntity(plugin, entity)) {
                    continue;
                }
                total++;
                DisplayEntities.ManagedEntityTag tag = DisplayEntities.readManagedTag(plugin, entity);
                String tableId = tag == null ? null : tag.tableId();
                if (tableId == null || !activeTableIds.contains(tableId)) {
                    orphans.add(entity.getUniqueId().toString());
                } else {
                    perTable.merge(tableId, 1, Integer::sum);
                }
            }
        }
        return new EntityReport(total, perTable, orphans);
    }

    /** Removes managed entities whose table tag no longer matches an active table. Returns removed count. */
    public static int cleanupOrphans(Plugin plugin, Set<String> activeTableIds) {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!DisplayEntities.isManagedEntity(plugin, entity)) {
                    continue;
                }
                DisplayEntities.ManagedEntityTag tag = DisplayEntities.readManagedTag(plugin, entity);
                String tableId = tag == null ? null : tag.tableId();
                if (tableId == null || !activeTableIds.contains(tableId)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }
}
