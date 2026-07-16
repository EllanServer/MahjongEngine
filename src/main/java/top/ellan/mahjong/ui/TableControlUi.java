package top.ellan.mahjong.ui;

import top.ellan.mahjong.compat.PaperCompatibility;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.MahjongTableSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class TableControlUi {
    private static final int INVENTORY_SIZE = 45;

    private TableControlUi() {
    }

    public static boolean isTableControlInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof TableControlHolder;
    }

    public static void open(Player player, MahjongTableSession session) {
        if (player == null || session == null) {
            return;
        }
        session.plugin().scheduler().runEntity(player, () -> player.openInventory(createInventory(player, session)));
    }

    public static void handleClick(InventoryClickEvent event, MahjongTableManager manager) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory topInventory = PaperCompatibility.getTopInventory(event);
        if (!(topInventory.getHolder() instanceof TableControlHolder holder)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= topInventory.getSize()) {
            return;
        }
        TableAction action = TableAction.bySlot(event.getRawSlot());
        if (action == null) {
            return;
        }

        MahjongTableSession session = manager.resolveTableById(holder.tableId());
        if (session == null) {
            player.closeInventory();
            return;
        }
        boolean keepOpen = handleAction(player, session, manager, action, event.isShiftClick());
        if (keepOpen) {
            open(player, session);
        }
    }

    private static boolean handleAction(Player player, MahjongTableSession session, MahjongTableManager manager, TableAction action, boolean confirmed) {
        MessageService messages = session.plugin().messages();
        boolean managerAllowed = manager.canManageTable(player, session);
        return switch (action) {
            case READY -> {
                if (session.seatOf(player.getUniqueId()) == null) {
                    messages.send(player, "command.ready_blocked");
                    yield true;
                }
                manager.sendReadyResult(player, session.toggleReady(player.getUniqueId()));
                yield true;
            }
            case LEAVE -> {
                MahjongTableManager.LeaveResult result = manager.leave(player.getUniqueId());
                manager.sendLeaveResult(player, result);
                player.closeInventory();
                yield false;
            }
            case RULES -> {
                RuleSettingsUi.open(player, session);
                yield false;
            }
            case SETTLEMENT -> {
                messages.send(player, session.openSettlementUi(player) ? "command.settlement_opened" : "command.settlement_unavailable");
                yield false;
            }
            case REFRESH -> {
                if (!managerAllowed) {
                    messages.send(player, "command.table_owner_required");
                    yield true;
                }
                session.render();
                messages.send(player, "command.rendered");
                yield true;
            }
            case ADD_BOT -> {
                if (!managerAllowed) {
                    messages.send(player, "command.table_owner_required");
                    yield true;
                }
                messages.send(player, session.addBot() ? "command.bot_added" : "command.bot_add_failed");
                yield true;
            }
            case REMOVE_BOT -> {
                if (!managerAllowed) {
                    messages.send(player, "command.table_owner_required");
                    yield true;
                }
                messages.send(player, session.removeBot() ? "command.bot_removed" : "command.bot_remove_failed");
                yield true;
            }
            case START_ROUND -> {
                if (!managerAllowed) {
                    messages.send(player, "command.table_owner_required");
                    yield true;
                }
                if (session.isStarted() || session.isRoundStartInProgress() || session.size() < 4 || session.readyCount() < 4) {
                    messages.send(player, "command.table_start_requirements");
                    yield true;
                }
                session.startRound();
                messages.send(player, "command.round_started");
                yield true;
            }
            case FORCE_END -> {
                if (!player.hasPermission("mahjongpaper.admin")) {
                    messages.send(player, "command.admin_required");
                    yield true;
                }
                if (!session.isStarted()) {
                    messages.send(player, "command.table_not_started");
                    yield true;
                }
                if (!confirmed) {
                    messages.send(player, "command.table_confirm_danger");
                    yield true;
                }
                manager.forceEndTable(session.id());
                messages.send(player, "command.forceend_success", messages.tag("table_id", session.id()));
                yield true;
            }
            case DELETE -> {
                if (!manager.canDeleteTable(player, session)) {
                    messages.send(player, session.isStarted() ? "command.table_delete_locked" : "command.table_owner_required");
                    yield true;
                }
                if (!manager.canBreakTable(player, session)) {
                    messages.send(player, "command.deletetable_protected");
                    yield true;
                }
                if (!confirmed) {
                    messages.send(player, "command.table_confirm_danger");
                    yield true;
                }
                manager.deleteTable(session.id());
                messages.send(player, "command.deletetable_success", messages.tag("table_id", session.id()));
                player.closeInventory();
                yield false;
            }
        };
    }

    private static Inventory createInventory(Player player, MahjongTableSession session) {
        MessageService messages = session.plugin().messages();
        Locale locale = messages.resolveLocale(player);
        TableControlHolder holder = new TableControlHolder(session.id());
        Inventory inventory = Bukkit.createInventory(
            holder,
            INVENTORY_SIZE,
            messages.render(locale, "ui.table_title", messages.tag("table_id", session.id()))
        );
        holder.bind(inventory);
        fillBackground(inventory);

        boolean managerAllowed = player.hasPermission("mahjongpaper.admin") || session.isOwner(player.getUniqueId());
        inventory.setItem(4, summaryItem(player, session, locale));
        inventory.setItem(10, actionItem(session, locale, TableAction.READY, Material.LIME_DYE, session.seatOf(player.getUniqueId()) == null || session.isStarted()));
        inventory.setItem(12, actionItem(session, locale, TableAction.LEAVE, Material.OAK_DOOR, false));
        inventory.setItem(14, actionItem(session, locale, TableAction.SETTLEMENT, Material.BOOK, session.lastResolution() == null));
        inventory.setItem(16, actionItem(session, locale, TableAction.REFRESH, Material.BELL, !managerAllowed));
        inventory.setItem(20, actionItem(session, locale, TableAction.RULES, Material.COMPARATOR, false));
        inventory.setItem(22, actionItem(session, locale, TableAction.ADD_BOT, Material.PLAYER_HEAD, !managerAllowed || session.isStarted()));
        inventory.setItem(24, actionItem(session, locale, TableAction.REMOVE_BOT, Material.SKELETON_SKULL, !managerAllowed || session.isStarted() || session.botCount() == 0));
        inventory.setItem(28, actionItem(session, locale, TableAction.START_ROUND, Material.EMERALD_BLOCK, !managerAllowed || session.isStarted() || session.size() < 4 || session.readyCount() < 4));
        inventory.setItem(30, actionItem(session, locale, TableAction.FORCE_END, Material.REDSTONE_BLOCK, !player.hasPermission("mahjongpaper.admin") || !session.isStarted()));
        inventory.setItem(32, actionItem(session, locale, TableAction.DELETE, Material.BARRIER, !managerAllowed || (session.isStarted() && !player.hasPermission("mahjongpaper.admin"))));
        return inventory;
    }

    private static void fillBackground(Inventory inventory) {
        ItemStack filler = namedItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private static ItemStack summaryItem(Player player, MahjongTableSession session, Locale locale) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        UUID owner = session.owner();
        String ownerName = owner == null ? messages.plain(locale, "ui.table_owner.none") : session.displayName(owner, locale);
        lore.add(messages.render(locale, "ui.table_owner", messages.tag("owner", ownerName)));
        lore.add(messages.render(locale, "ui.table_seats", messages.number(locale, "seats", session.size()), messages.number(locale, "ready", session.readyCount())));
        lore.add(messages.render(locale, "ui.table_rules", messages.tag("rules", session.ruleSummary(locale))));
        if (!player.hasPermission("mahjongpaper.admin") && !session.isOwner(player.getUniqueId())) {
            lore.add(messages.render(locale, "ui.table_read_only"));
        }
        return namedItem(Material.NETHER_STAR, messages.render(locale, "ui.table_option.summary"), lore);
    }

    private static ItemStack actionItem(MahjongTableSession session, Locale locale, TableAction action, Material material, boolean inactive) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render(locale, inactive ? "ui.table_action_inactive" : "ui.table_action_click"));
        if (action.dangerous()) {
            lore.add(messages.render(locale, "ui.table_action_shift_click"));
        }
        if (session.isStarted() && action.lobbyOnly()) {
            lore.add(messages.render(locale, "ui.rule_locked"));
        }
        return namedItem(inactive ? Material.GRAY_DYE : material, messages.render(locale, action.labelKey()), lore);
    }

    private static ItemStack namedItem(Material material, Component name, List<Component> loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.colorIfAbsent(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        if (!loreLines.isEmpty()) {
            meta.lore(loreLines.stream()
                .map(line -> line.colorIfAbsent(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
                .toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    private enum TableAction {
        READY(10, "ui.table_option.ready", true),
        LEAVE(12, "ui.table_option.leave", false),
        SETTLEMENT(14, "ui.table_option.settlement", false),
        REFRESH(16, "ui.table_option.refresh", false),
        RULES(20, "ui.table_option.rules", true),
        ADD_BOT(22, "ui.table_option.add_bot", true),
        REMOVE_BOT(24, "ui.table_option.remove_bot", true),
        START_ROUND(28, "ui.table_option.start", true),
        FORCE_END(30, "ui.table_option.force_end", false, true),
        DELETE(32, "ui.table_option.delete", false, true);

        private final int slot;
        private final String labelKey;
        private final boolean lobbyOnly;
        private final boolean dangerous;

        TableAction(int slot, String labelKey, boolean lobbyOnly) {
            this(slot, labelKey, lobbyOnly, false);
        }

        TableAction(int slot, String labelKey, boolean lobbyOnly, boolean dangerous) {
            this.slot = slot;
            this.labelKey = labelKey;
            this.lobbyOnly = lobbyOnly;
            this.dangerous = dangerous;
        }

        private String labelKey() {
            return this.labelKey;
        }

        private boolean lobbyOnly() {
            return this.lobbyOnly;
        }

        private boolean dangerous() {
            return this.dangerous;
        }

        private static TableAction bySlot(int slot) {
            for (TableAction action : values()) {
                if (action.slot == slot) {
                    return action;
                }
            }
            return null;
        }
    }

    private static final class TableControlHolder implements InventoryHolder {
        private final String tableId;
        private Inventory inventory;

        private TableControlHolder(String tableId) {
            this.tableId = tableId;
        }

        private String tableId() {
            return this.tableId;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
    }
}
