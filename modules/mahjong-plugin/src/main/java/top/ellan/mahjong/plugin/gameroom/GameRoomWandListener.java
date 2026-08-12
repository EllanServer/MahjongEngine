package top.ellan.mahjong.plugin.gameroom;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;

/** Admin-only room selection; preview particles are sent once to that admin's client. */
public final class GameRoomWandListener implements Listener {
    private static final Material WAND_MATERIAL = Material.BLAZE_ROD;
    private final GameRoomSelectionService selections;
    private final LocalizedMessageCatalog messages;
    private final NamespacedKey wandKey;

    public GameRoomWandListener(
            Plugin plugin,
            GameRoomSelectionService selections,
            LocalizedMessageCatalog messages) {
        this.selections = Objects.requireNonNull(selections, "selections");
        this.messages = Objects.requireNonNull(messages, "messages");
        wandKey = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "room_wand");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("mahjongpaper.admin") || !isWand(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        GameRoomSelectionService.Selection selection =
                action == Action.LEFT_CLICK_BLOCK
                        ? selections.setFirst(player.getUniqueId(), clicked.getLocation())
                        : selections.setSecond(player.getUniqueId(), clicked.getLocation());
        event.setCancelled(true);
        preview(player, selection);
        String key =
                action == Action.LEFT_CLICK_BLOCK
                        ? "mahjongpaper.gameroom.wand.first"
                        : "mahjongpaper.gameroom.wand.second";
        String fallback =
                action == Action.LEFT_CLICK_BLOCK
                        ? "First corner: %s, %s, %s"
                        : "Second corner: %s, %s, %s";
        player.sendMessage(
                Component.text(
                        String.format(
                                player.locale(),
                                messages.resolve(player.locale(), key, fallback),
                                clicked.getX(),
                                clicked.getY(),
                                clicked.getZ()),
                        NamedTextColor.AQUA));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.clear(event.getPlayer().getUniqueId());
    }

    public ItemStack createWand(Locale locale) {
        ItemStack wand = new ItemStack(WAND_MATERIAL);
        var meta = wand.getItemMeta();
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        meta.displayName(
                Component.text(
                                messages.resolve(
                                        locale,
                                        "mahjongpaper.gameroom.wand.title",
                                        "Mahjong Room Wand"),
                                NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD));
        meta.lore(
                List.of(
                        Component.text(
                                messages.resolve(
                                        locale,
                                        "mahjongpaper.gameroom.wand.left",
                                        "Left-click: set first corner"),
                                NamedTextColor.GRAY),
                        Component.text(
                                messages.resolve(
                                        locale,
                                        "mahjongpaper.gameroom.wand.right",
                                        "Right-click: set second corner"),
                                NamedTextColor.GRAY),
                        Component.text(
                                messages.resolve(
                                        locale,
                                        "mahjongpaper.gameroom.wand.finish",
                                        "Then run /mahjong room create <id>"),
                                NamedTextColor.GRAY)));
        wand.setItemMeta(meta);
        return wand;
    }

    private boolean isWand(ItemStack item) {
        return item != null
                && item.getType() == WAND_MATERIAL
                && item.hasItemMeta()
                && item.getItemMeta()
                        .getPersistentDataContainer()
                        .has(wandKey, PersistentDataType.BYTE);
    }

    private void preview(Player player, GameRoomSelectionService.Selection selection) {
        for (org.bukkit.Location point : selections.previewPoints(selection)) {
            player.spawnParticle(
                    Particle.END_ROD,
                    point,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D);
        }
    }
}
