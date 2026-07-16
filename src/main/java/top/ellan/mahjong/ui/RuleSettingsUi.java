package top.ellan.mahjong.ui;

import top.ellan.mahjong.compat.PaperCompatibility;
import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.table.core.MahjongTableManager;
import top.ellan.mahjong.table.core.MahjongTableSession;
import top.ellan.mahjong.model.MahjongVariant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

public final class RuleSettingsUi {
    private static final int INVENTORY_SIZE = 54;
    private static final List<String> PRESETS = List.of("MAJSOUL_TONPUU", "MAJSOUL_HANCHAN", "GB", "SICHUAN");

    private RuleSettingsUi() {
    }

    public static boolean isRuleInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof RuleSettingsHolder;
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
        if (!(topInventory.getHolder() instanceof RuleSettingsHolder holder)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= topInventory.getSize()) {
            return;
        }
        RuleAction action = RuleAction.bySlot(event.getRawSlot());
        if (action == null) {
            return;
        }
        MahjongTableSession session = manager.resolveTableById(holder.tableId());
        if (session == null || (manager.sessionForViewer(player.getUniqueId()) != session && !manager.canManageTable(player, session))) {
            player.closeInventory();
            return;
        }
        if (!manager.canManageTable(player, session)) {
            session.plugin().messages().send(player, "command.table_owner_required");
            open(player, session);
            return;
        }
        if (session.isStarted()) {
            session.plugin().messages().send(player, "ui.rule_locked");
            open(player, session);
            return;
        }
        if (action.riichiOnly() && session.currentVariant() != MahjongVariant.RIICHI) {
            open(player, session);
            return;
        }
        if (action == RuleAction.LOCAL_YAKU) {
            open(player, session);
            return;
        }
        if (!applyAction(session, action, event.isRightClick(), event.isShiftClick())) {
            session.plugin().messages().send(player, "command.rule_update_failed");
        }
        open(player, session);
    }

    private static Inventory createInventory(Player player, MahjongTableSession session) {
        MessageService messages = session.plugin().messages();
        Locale locale = messages.resolveLocale(player);
        RuleSettingsHolder holder = new RuleSettingsHolder(session.id());
        Inventory inventory = Bukkit.createInventory(
            holder,
            INVENTORY_SIZE,
            messages.render(locale, "ui.rule_title", messages.tag("table_id", session.id()))
        );
        holder.bind(inventory);
        fillBackground(inventory);
        MahjongRule rule = session.configuredRuleSnapshot();
        MahjongVariant variant = session.currentVariant();

        inventory.setItem(4, summaryItem(session, locale));
        inventory.setItem(10, optionItem(session, locale, RuleAction.PRESET, Material.NETHER_STAR, presetLabel(session, locale), false));
        inventory.setItem(12, optionItem(session, locale, RuleAction.VARIANT, Material.COMPASS, variantLabel(session, locale, variant), false));
        inventory.setItem(14, optionItem(session, locale, RuleAction.LENGTH, Material.MAP, lengthLabel(session, locale, rule.getLength()), false));
        inventory.setItem(16, optionItem(session, locale, RuleAction.THINKING, Material.CLOCK, thinkingLabel(session, locale, rule.getThinkingTime()), false));
        inventory.setItem(19, numberItem(session, locale, RuleAction.STARTING_POINTS, Material.EMERALD, rule.getStartingPoints(), 1000));
        inventory.setItem(21, numberItem(session, locale, RuleAction.MIN_POINTS_TO_WIN, Material.TARGET, rule.getMinPointsToWin(), 1000));
        inventory.setItem(23, optionItem(session, locale, RuleAction.SPECTATE, Material.ENDER_EYE, booleanLabel(session, locale, rule.getSpectate()), false));
        inventory.setItem(25, optionItem(session, locale, RuleAction.MINIMUM_HAN, Material.GOLD_INGOT, minimumHanLabel(session, locale, rule.getMinimumHan()), variant != MahjongVariant.RIICHI));
        inventory.setItem(28, optionItem(session, locale, RuleAction.RED_FIVE, Material.RED_DYE, redFiveLabel(session, locale, rule.getRedFive()), variant != MahjongVariant.RIICHI));
        inventory.setItem(30, optionItem(session, locale, RuleAction.OPEN_TANYAO, Material.BAMBOO, booleanLabel(session, locale, rule.getOpenTanyao()), variant != MahjongVariant.RIICHI));
        inventory.setItem(34, optionItem(session, locale, RuleAction.RON_MODE, Material.CROSSBOW, ronModeLabel(session, locale, rule.getRonMode()), variant != MahjongVariant.RIICHI));
        inventory.setItem(40, optionItem(session, locale, RuleAction.RIICHI_PROFILE, Material.WRITABLE_BOOK, riichiProfileLabel(session, locale, rule.getRiichiProfile()), variant != MahjongVariant.RIICHI));
        return inventory;
    }

    private static void fillBackground(Inventory inventory) {
        ItemStack filler = namedItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private static ItemStack summaryItem(MahjongTableSession session, Locale locale) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render(locale, "ui.rule_summary", messages.tag("value", session.ruleSummary(locale))));
        if (session.isStarted()) {
            lore.add(messages.render(locale, "ui.rule_locked"));
        }
        return namedItem(Material.PAPER, messages.render(locale, "ui.rule_option.summary"), lore);
    }

    private static ItemStack optionItem(
        MahjongTableSession session,
        Locale locale,
        RuleAction action,
        Material material,
        String currentValue,
        boolean inactive
    ) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render(locale, "ui.rule_current", messages.tag("value", currentValue)));
        lore.add(messages.render(locale, inactive ? "ui.rule_inactive" : "ui.rule_click_cycle"));
        if (session.isStarted()) {
            lore.add(messages.render(locale, "ui.rule_locked"));
        }
        return namedItem(inactive ? Material.GRAY_DYE : material, messages.render(locale, action.labelKey()), lore);
    }

    private static ItemStack numberItem(
        MahjongTableSession session,
        Locale locale,
        RuleAction action,
        Material material,
        int currentValue,
        int step
    ) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render(locale, "ui.rule_current", messages.number(locale, "value", currentValue)));
        lore.add(messages.render(locale, "ui.rule_click_number", messages.number(locale, "step", step)));
        if (session.isStarted()) {
            lore.add(messages.render(locale, "ui.rule_locked"));
        }
        return namedItem(material, messages.render(locale, action.labelKey()), lore);
    }

    private static boolean applyAction(MahjongTableSession session, RuleAction action, boolean reverse, boolean largeStep) {
        MahjongRule rule = session.configuredRuleSnapshot();
        return switch (action) {
            case PRESET -> session.setRuleOption("preset", next(PRESETS, currentPreset(session), reverse));
            case VARIANT -> session.setRuleOption("variant", next(MahjongVariant.values(), session.currentVariant(), reverse).name());
            case LENGTH -> session.setRuleOption("length", next(MahjongRule.GameLength.values(), rule.getLength(), reverse).name());
            case THINKING -> session.setRuleOption("thinkingTime", next(MahjongRule.ThinkingTime.values(), rule.getThinkingTime(), reverse).name());
            case STARTING_POINTS -> session.setRuleOption("startingPoints", String.valueOf(adjustPoints(rule.getStartingPoints(), reverse, largeStep)));
            case MIN_POINTS_TO_WIN -> session.setRuleOption("minPointsToWin", String.valueOf(adjustPoints(rule.getMinPointsToWin(), reverse, largeStep)));
            case SPECTATE -> session.setRuleOption("spectate", String.valueOf(!rule.getSpectate()));
            case MINIMUM_HAN -> session.setRuleOption("minimumHan", next(MahjongRule.MinimumHan.values(), rule.getMinimumHan(), reverse).name());
            case RED_FIVE -> session.setRuleOption("redFive", next(MahjongRule.RedFive.values(), rule.getRedFive(), reverse).name());
            case OPEN_TANYAO -> session.setRuleOption("openTanyao", String.valueOf(!rule.getOpenTanyao()));
            case LOCAL_YAKU -> session.setRuleOption("localYaku", String.valueOf(!rule.getLocalYaku()));
            case RON_MODE -> session.setRuleOption("ronMode", next(MahjongRule.RonMode.values(), rule.getRonMode(), reverse).name());
            case RIICHI_PROFILE -> session.setRuleOption("riichiProfile", next(MahjongRule.RiichiProfile.values(), rule.getRiichiProfile(), reverse).name());
        };
    }

    private static String currentPreset(MahjongTableSession session) {
        if (session.currentVariant() == MahjongVariant.GB) {
            return "GB";
        }
        if (session.currentVariant() == MahjongVariant.SICHUAN) {
            return "SICHUAN";
        }
        return session.configuredRuleSnapshot().getLength() == MahjongRule.GameLength.EAST ? "MAJSOUL_TONPUU" : "MAJSOUL_HANCHAN";
    }

    private static int adjustPoints(int currentValue, boolean decrease, boolean largeStep) {
        int step = largeStep ? 5000 : 1000;
        int updated = currentValue + (decrease ? -step : step);
        return Math.max(1000, Math.min(100000, updated));
    }

    private static <E extends Enum<E>> E next(E[] values, E currentValue, boolean reverse) {
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == currentValue) {
                index = i;
                break;
            }
        }
        return values[Math.floorMod(index + (reverse ? -1 : 1), values.length)];
    }

    private static String next(List<String> values, String currentValue, boolean reverse) {
        int index = values.indexOf(currentValue);
        if (index < 0) {
            index = 0;
        }
        return values.get(Math.floorMod(index + (reverse ? -1 : 1), values.size()));
    }

    private static String presetLabel(MahjongTableSession session, Locale locale) {
        return session.plugin().messages().plain(locale, "ui.rule_preset." + currentPreset(session).toLowerCase(Locale.ROOT));
    }

    private static String variantLabel(MahjongTableSession session, Locale locale, MahjongVariant variant) {
        return session.plugin().messages().plain(locale, variant.translationKey());
    }

    private static String lengthLabel(MahjongTableSession session, Locale locale, MahjongRule.GameLength length) {
        return session.plugin().messages().plain(locale, "rule.length." + length.name().toLowerCase(Locale.ROOT));
    }

    private static String thinkingLabel(MahjongTableSession session, Locale locale, MahjongRule.ThinkingTime thinkingTime) {
        return session.plugin().messages().plain(locale, "rule.thinking." + thinkingTime.name().toLowerCase(Locale.ROOT));
    }

    private static String minimumHanLabel(MahjongTableSession session, Locale locale, MahjongRule.MinimumHan minimumHan) {
        return session.plugin().messages().plain(locale, "rule.minimum_han." + minimumHan.name().toLowerCase(Locale.ROOT));
    }

    private static String redFiveLabel(MahjongTableSession session, Locale locale, MahjongRule.RedFive redFive) {
        return session.plugin().messages().plain(locale, "rule.red_five." + redFive.name().toLowerCase(Locale.ROOT));
    }

    private static String booleanLabel(MahjongTableSession session, Locale locale, boolean value) {
        return session.plugin().messages().plain(locale, value ? "common.true" : "common.false");
    }

    private static String ronModeLabel(MahjongTableSession session, Locale locale, MahjongRule.RonMode ronMode) {
        return session.plugin().messages().plain(locale, "rule.ron_mode." + ronMode.name().toLowerCase(Locale.ROOT));
    }

    private static String riichiProfileLabel(MahjongTableSession session, Locale locale, MahjongRule.RiichiProfile profile) {
        return session.plugin().messages().plain(locale, "rule.riichi_profile." + profile.name().toLowerCase(Locale.ROOT));
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

    private enum RuleAction {
        PRESET(10, "ui.rule_option.preset", false),
        VARIANT(12, "ui.rule_option.variant", false),
        LENGTH(14, "ui.rule_option.length", false),
        THINKING(16, "ui.rule_option.thinking", false),
        STARTING_POINTS(19, "ui.rule_option.starting_points", false),
        MIN_POINTS_TO_WIN(21, "ui.rule_option.min_points_to_win", false),
        SPECTATE(23, "ui.rule_option.spectate", false),
        MINIMUM_HAN(25, "ui.rule_option.minimum_han", true),
        RED_FIVE(28, "ui.rule_option.red_five", true),
        OPEN_TANYAO(30, "ui.rule_option.open_tanyao", true),
        LOCAL_YAKU(32, "ui.rule_option.local_yaku", true),
        RON_MODE(34, "ui.rule_option.ron_mode", true),
        RIICHI_PROFILE(40, "ui.rule_option.riichi_profile", true);

        private final int slot;
        private final String labelKey;
        private final boolean riichiOnly;

        RuleAction(int slot, String labelKey, boolean riichiOnly) {
            this.slot = slot;
            this.labelKey = labelKey;
            this.riichiOnly = riichiOnly;
        }

        private String labelKey() {
            return this.labelKey;
        }

        private boolean riichiOnly() {
            return this.riichiOnly;
        }

        private static RuleAction bySlot(int slot) {
            for (RuleAction action : values()) {
                if (action.slot == slot) {
                    return action;
                }
            }
            return null;
        }
    }

    private static final class RuleSettingsHolder implements InventoryHolder {
        private final String tableId;
        private Inventory inventory;

        private RuleSettingsHolder(String tableId) {
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
