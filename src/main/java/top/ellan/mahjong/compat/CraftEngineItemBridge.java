package top.ellan.mahjong.compat;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class CraftEngineItemBridge {
    private final CraftEngineBridgeContext context;
    private final boolean preferCustomItems;
    private final Map<String, ItemStack> customItemCache = new ConcurrentHashMap<>();

    CraftEngineItemBridge(CraftEngineBridgeContext context, boolean preferCustomItems) {
        this.context = context;
        this.preferCustomItems = preferCustomItems;
    }

    ItemStack resolveTileItem(MahjongVariant variant, MahjongTile tile, boolean faceDown) {
        if (!this.preferCustomItems || !this.isCraftEngineAvailable()) {
            return null;
        }

        String itemId = this.customItemId(variant, tile, faceDown);
        ItemStack resolved = this.buildCustomItem(itemId);
        if (resolved != null) {
            return resolved;
        }
        if (!faceDown && tile != MahjongTile.UNKNOWN) {
            String fallbackItemId = this.customItemId(variant, MahjongTile.UNKNOWN, false);
            if (!Objects.equals(fallbackItemId, itemId)) {
                return this.buildCustomItem(fallbackItemId);
            }
        }
        return null;
    }

    String customItemId(MahjongVariant variant, MahjongTile tile, boolean faceDown) {
        return CraftEngineTileItemResolver.resolve(this.context.plugin().settings().craftEngineTileItemIdPrefix(variant), tile, faceDown);
    }

    private ItemStack buildCustomItem(String itemId) {
        ItemStack cached = this.customItemCache.get(itemId);
        if (cached != null) {
            return cached.clone();
        }

        try {
            BukkitItemDefinition customItem = CraftEngineItems.byId(itemId);
            if (customItem == null) {
                return null;
            }
            ItemStack built = customItem.buildBukkitItem();
            this.customItemCache.put(itemId, built.clone());
            return built;
        } catch (RuntimeException | LinkageError exception) {
            this.context.plugin().getLogger().warning(
                "CraftEngine was detected, but MahjongPaper could not build CraftEngine custom items. Falling back to direct item_model items."
            );
            this.context.plugin().debug().log(
                "lifecycle",
                "CraftEngine item API failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
            return null;
        }
    }

    private boolean isCraftEngineAvailable() {
        Plugin craftEngine = this.context.craftEnginePlugin();
        return craftEngine != null && craftEngine.isEnabled();
    }
}
