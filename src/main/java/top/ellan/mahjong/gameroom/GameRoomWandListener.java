package top.ellan.mahjong.gameroom;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.i18n.MessageService;

public final class GameRoomWandListener implements Listener {
    static final Material WAND_MATERIAL = Material.BLAZE_ROD;
    private static final String WAND_NBT_KEY = "mahjongpaper:wand";

    private final GameRoomSelectionService selectionService;
    private final GameRoomSelectionPreviewService previewService;
    private final MessageService messages;
    private final Supplier<PluginSettings> settingsSupplier;

    public GameRoomWandListener(
        GameRoomSelectionService selectionService,
        GameRoomSelectionPreviewService previewService,
        MessageService messages,
        Supplier<PluginSettings> settingsSupplier
    ) {
        this.selectionService = selectionService;
        this.previewService = previewService;
        this.messages = messages;
        this.settingsSupplier = settingsSupplier;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isWand(item)) {
            return;
        }
        if (!player.hasPermission("mahjongpaper.admin")) {
            return;
        }

        Action action = event.getAction();
        UUID playerId = player.getUniqueId();

        if (action == Action.LEFT_CLICK_BLOCK) {
            Location loc = event.getClickedBlock().getLocation();
            this.selectionService.setFirst(playerId, loc);
            this.previewSelection(player);
            this.messages.send(player, "gameroom.wand_first",
                this.messages.tag("x", String.valueOf(loc.getBlockX())),
                this.messages.tag("y", String.valueOf(loc.getBlockY())),
                this.messages.tag("z", String.valueOf(loc.getBlockZ()))
            );
            event.setCancelled(true);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            Location loc = event.getClickedBlock().getLocation();
            this.selectionService.setSecond(playerId, loc);
            this.previewSelection(player);
            this.messages.send(player, "gameroom.wand_second",
                this.messages.tag("x", String.valueOf(loc.getBlockX())),
                this.messages.tag("y", String.valueOf(loc.getBlockY())),
                this.messages.tag("z", String.valueOf(loc.getBlockZ()))
            );
            GameRoomSelectionService.Selection selection = this.selectionService.selection(playerId);
            if (selection != null && selection.complete()) {
                this.messages.send(player, "gameroom.wand_preview");
            }
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        this.selectionService.clear(playerId);
        if (this.previewService != null) {
            this.previewService.cancel(playerId);
        }
    }

    private void previewSelection(Player player) {
        if (this.previewService != null) {
            this.previewService.showSelection(player);
        }
    }

    private boolean isEnabled() {
        PluginSettings settings = this.settingsSupplier == null ? null : this.settingsSupplier.get();
        return settings != null && settings.gameRooms().enabled();
    }

    public static ItemStack createWand() {
        ItemStack wand = new ItemStack(WAND_MATERIAL);
        var meta = wand.getItemMeta();
        if (meta != null) {
            var persistentDataContainer = meta.getPersistentDataContainer();
            org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey("mahjongpaper", "wand");
            persistentDataContainer.set(key, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            meta.displayName(
                Component.text("Mahjong Room Wand", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
            );
            meta.lore(List.of(
                Component.text("Left-click: Set first corner", NamedTextColor.GRAY),
                Component.text("Right-click: Set second corner", NamedTextColor.GRAY),
                Component.text("Then use /mahjong room create <id>", NamedTextColor.GRAY)
            ));
            wand.setItemMeta(meta);
        }
        return wand;
    }

    public static boolean isWand(ItemStack item) {
        if (item == null || item.getType() != WAND_MATERIAL) {
            return false;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        var container = meta.getPersistentDataContainer();
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey("mahjongpaper", "wand");
        return container.has(key, org.bukkit.persistence.PersistentDataType.BYTE);
    }
}
