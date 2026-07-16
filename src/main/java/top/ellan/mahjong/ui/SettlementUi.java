package top.ellan.mahjong.ui;

import top.ellan.mahjong.i18n.MessageService;
import top.ellan.mahjong.compat.PaperCompatibility;
import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.RoundResolution;
import top.ellan.mahjong.riichi.model.RankedScoreItem;
import top.ellan.mahjong.riichi.model.ScoreSettlement;
import top.ellan.mahjong.riichi.model.YakuSettlement;
import top.ellan.mahjong.table.core.MahjongTableSession;
import top.ellan.mahjong.table.core.TableFinalStanding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.NumberFormat;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class SettlementUi {
    private static final int INVENTORY_SIZE = 54;

    private SettlementUi() {
    }

    public static boolean isSettlementInventory(Inventory inventory) {
        return inventory.getHolder() instanceof SettlementHolder;
    }

    public static void open(Player player, MahjongTableSession session) {
        RoundResolution resolution = session.lastResolution();
        if (resolution == null) {
            return;
        }
        session.plugin().scheduler().runEntity(player, () -> player.openInventory(createInventory(player, session, resolution)));
    }

    private static Inventory createInventory(Player player, MahjongTableSession session, RoundResolution resolution) {
        MessageService messages = session.plugin().messages();
        Locale locale = messages.resolveLocale(player);
        SettlementHolder holder = new SettlementHolder(session.id());
        Inventory inventory = Bukkit.createInventory(
            holder,
            INVENTORY_SIZE,
            messages.render(locale, "ui.settlement_title", messages.tag("title", resolutionTitleLabel(session, locale, resolution.getTitle())))
        );
        holder.bind(inventory);
        fillBackground(inventory);
        inventory.setItem(4, summaryItem(session, resolution, locale));
        placeIndicatorRow(inventory, locale, session, 0, "ui.dora_indicators", session.doraIndicators());
        placeIndicatorRow(inventory, locale, session, 9, "ui.uradora_indicators", session.uraDoraIndicators());
        placeScoreItems(inventory, locale, session, resolution.getScoreSettlement());
        placeSettlementDetails(inventory, locale, session, resolution);
        return inventory;
    }

    private static void fillBackground(Inventory inventory) {
        ItemStack filler = namedItem(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private static ItemStack summaryItem(MahjongTableSession session, RoundResolution resolution, Locale locale) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = summaryLore(session, resolution, locale);
        return namedItem(Material.NETHER_STAR, messages.render(locale, "ui.summary_title", messages.tag("title", resolutionTitleLabel(session, locale, resolution.getTitle()))), lore);
    }

    static List<Component> summaryLore(MahjongTableSession session, RoundResolution resolution, Locale locale) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render(locale, "ui.summary.round", messages.tag("value", session.roundDisplay(locale))));
        lore.add(messages.render(locale, "ui.summary.dealer", messages.tag("value", session.dealerName(locale))));
        lore.add(messages.render(locale, "ui.summary.honba", messages.number(locale, "value", session.honbaCount())));
        lore.add(messages.render(locale, "ui.summary.riichi_pool", messages.number(locale, "value", session.riichiPoolCount())));
        if (!resolution.getYakuSettlements().isEmpty()) {
            List<String> winners = resolution.getYakuSettlements().stream()
                .map(YakuSettlement::getDisplayName)
                .distinct()
                .toList();
            lore.add(messages.render(
                locale,
                winners.size() > 1 ? "ui.summary.winners" : "ui.summary.winner",
                messages.tag("value", String.join(", ", winners))
            ));
        }
        if (resolution.getDraw() != null) {
            lore.add(messages.render(locale, "ui.summary.draw_type", messages.tag("value", drawLabel(session, locale, resolution.getDraw().name()))));
        }
        if (resolution.getScoreSettlement() != null) {
            lore.add(messages.render(locale, "ui.summary.settled_players", messages.number(locale, "value", resolution.getScoreSettlement().getScoreList().size())));
        }
        return lore;
    }

    private static void placeIndicatorRow(Inventory inventory, Locale locale, MahjongTableSession session, int startSlot, String key, List<MahjongTile> tiles) {
        MessageService messages = session.plugin().messages();
        Component label = messages.render(locale, key);
        for (int i = 0; i < 5; i++) {
            if (i < tiles.size()) {
                inventory.setItem(startSlot + i, tileItem(session, tiles.get(i), false, label));
            } else {
                inventory.setItem(startSlot + i, namedItem(Material.GRAY_STAINED_GLASS_PANE, label, List.of(messages.render(locale, "ui.no_tile_slot"))));
            }
        }
    }

    private static void placeScoreItems(Inventory inventory, Locale locale, MahjongTableSession session, ScoreSettlement settlement) {
        MessageService messages = session.plugin().messages();
        if (settlement == null) {
            inventory.setItem(
                22,
                namedItem(
                    Material.PAPER,
                    messages.render(locale, "ui.no_score_movement"),
                    List.of(messages.render(locale, "ui.no_score_movement_desc"))
                )
            );
            return;
        }

        int[] slots = {18, 20, 24, 26};
        List<RankedScoreItem> ranked = settlement.getRankedScoreList();
        for (int i = 0; i < ranked.size() && i < slots.length; i++) {
            RankedScoreItem item = ranked.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(messages.render(locale, "ui.score.start", messages.number(locale, "value", item.getScoreItem().getScoreOrigin())));
            lore.add(messages.render(locale, "ui.score.change", messages.tag("value", item.getScoreChangeText().isBlank() ? "0" : item.getScoreChangeText())));
            lore.add(messages.render(locale, "ui.score.total", messages.number(locale, "value", item.getScoreTotal())));
            if (!item.getRankFloatText().isBlank()) {
                lore.add(messages.render(locale, "ui.score.rank_shift", messages.tag("value", rankShiftLabel(session, locale, item.getRankFloatText()))));
            }
            if (session.isRoundFinished()) {
                TableFinalStanding standing = finalStanding(session, item.getScoreItem().getStringUUID());
                if (standing != null) {
                    lore.add(messages.render(locale, "ui.score.place", messages.number(locale, "value", standing.place())));
                    if (shouldShowMahjongSoulGameScore(session)) {
                        lore.add(messages.render(locale, "ui.score.majsoul", messages.tag("value", formatGameScore(locale, standing.gameScore()))));
                    }
                }
            }
            Material material = item.getScoreItem().getScoreChange() > 0
                ? Material.EMERALD
                : item.getScoreItem().getScoreChange() < 0 ? Material.REDSTONE : Material.NAME_TAG;
            inventory.setItem(slots[i], namedItem(material, Component.text(SettlementPaymentFormatter.displayName(session, item.getScoreItem().getStringUUID())), lore));
        }
    }

    static boolean shouldShowMahjongSoulGameScore(MahjongTableSession session) {
        return session != null && session.currentVariant() == MahjongVariant.RIICHI;
    }

    private static void placeSettlementDetails(Inventory inventory, Locale locale, MahjongTableSession session, RoundResolution resolution) {
        MessageService messages = session.plugin().messages();
        if (resolution.getYakuSettlements().isEmpty()) {
            inventory.setItem(
                40,
                namedItem(
                    Material.BOOK,
                    messages.render(locale, "ui.no_winning_hand"),
                    List.of(messages.render(locale, "ui.no_winning_hand_desc"))
                )
            );
            return;
        }

        int detailSlot = 36;
        for (YakuSettlement settlement : resolution.getYakuSettlements()) {
            if (detailSlot >= INVENTORY_SIZE) {
                break;
            }
            inventory.setItem(detailSlot, namedItem(Material.ENCHANTED_BOOK, Component.text(settlement.getDisplayName()), settlementLore(locale, session, settlement)));
            if (detailSlot + 1 < INVENTORY_SIZE) {
                inventory.setItem(detailSlot + 1, tileItem(session, toDisplayTile(settlement.getWinningTile().name()), false, messages.render(locale, "ui.winning_tile")));
            }
            detailSlot += 3;
        }
    }

    static List<Component> settlementLore(Locale locale, MahjongTableSession session, YakuSettlement settlement) {
        if (session.currentVariant() != top.ellan.mahjong.model.MahjongVariant.RIICHI) {
            return gbSettlementLore(locale, session, settlement);
        }
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render(locale, "ui.fu_han", messages.number(locale, "fu", settlement.getFu()), messages.number(locale, "han", settlement.getHan())));
        lore.add(messages.render(locale, "ui.score", messages.number(locale, "value", settlement.getScore())));
        lore.add(messages.render(locale, "ui.riichi", messages.tag("value", messages.plain(locale, settlement.getRiichi() ? "ui.yes" : "ui.no"))));
        lore.add(messages.render(locale, "ui.winning_tile_line", messages.tag("value", tileLabel(session, locale, settlement.getWinningTile().name()))));
        lore.add(messages.render(locale, "ui.hand", messages.tag("value", joinTiles(session, locale, settlement.getHands()))));
        if (!settlement.getFuuroList().isEmpty()) {
            List<String> melds = new ArrayList<>();
            settlement.getFuuroList().forEach(pair -> melds.add(meldPrefix(session, locale, pair.getFirst()) + ":" + joinTiles(session, locale, pair.getSecond())));
            lore.add(messages.render(locale, "ui.melds", messages.tag("value", String.join(" | ", melds))));
        }
        if (!settlement.getYakuList().isEmpty()) {
            settlement.getYakuList().forEach(yaku -> lore.add(messages.render(locale, "ui.yaku", messages.tag("value", yakuLabel(session, locale, yaku)))));
        }
        if (!settlement.getYakumanList().isEmpty()) {
            settlement.getYakumanList().forEach(yaku -> lore.add(messages.render(locale, "ui.yakuman", messages.tag("value", yakumanLabel(session, locale, yaku)))));
        }
        if (!settlement.getDoubleYakumanList().isEmpty()) {
            settlement.getDoubleYakumanList().forEach(yaku -> lore.add(messages.render(locale, "ui.double_yakuman", messages.tag("value", doubleYakumanLabel(session, locale, yaku.name())))));
        }
        if (settlement.getNagashiMangan()) {
            lore.add(messages.render(locale, "ui.nagashi_mangan"));
        }
        if (settlement.getRedFiveCount() > 0) {
            lore.add(messages.render(locale, "ui.red_fives", messages.number(locale, "value", settlement.getRedFiveCount())));
        }
        if (!settlement.getDoraIndicators().isEmpty()) {
            lore.add(messages.render(locale, "ui.dora", messages.tag("value", joinTiles(session, locale, settlement.getDoraIndicators()))));
        }
        if (!settlement.getUraDoraIndicators().isEmpty()) {
            lore.add(messages.render(locale, "ui.uradora", messages.tag("value", joinTiles(session, locale, settlement.getUraDoraIndicators()))));
        }
        SettlementPaymentFormatter.appendBreakdown(lore, locale, session, settlement);
        return lore;
    }

    private static List<Component> gbSettlementLore(Locale locale, MahjongTableSession session, YakuSettlement settlement) {
        MessageService messages = session.plugin().messages();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.render(locale, "ui.gb_total_fan", messages.number(locale, "value", settlement.getHan())));
        lore.add(messages.render(locale, "ui.score", messages.number(locale, "value", settlement.getScore())));
        lore.add(messages.render(locale, "ui.winning_tile_line", messages.tag("value", tileLabel(session, locale, settlement.getWinningTile().name()))));
        lore.add(messages.render(locale, "ui.hand", messages.tag("value", joinTiles(session, locale, settlement.getHands()))));
        if (!settlement.getFuuroList().isEmpty()) {
            List<String> melds = new ArrayList<>();
            settlement.getFuuroList().forEach(pair -> melds.add(meldPrefix(session, locale, pair.getFirst()) + ":" + joinTiles(session, locale, pair.getSecond())));
            lore.add(messages.render(locale, "ui.melds", messages.tag("value", String.join(" | ", melds))));
        }
        if (settlement.getYakuList().isEmpty()) {
            lore.add(messages.render(locale, "ui.gb_no_fan_breakdown"));
        } else {
            settlement.getYakuList().forEach(fan -> lore.add(messages.render(locale, "ui.gb_fan", messages.tag("value", fanLabel(session, locale, fan)))));
        }
        SettlementPaymentFormatter.appendBreakdown(lore, locale, session, settlement);
        return lore;
    }

    private static String joinTiles(MahjongTableSession session, Locale locale, List<?> tiles) {
        List<String> names = new ArrayList<>();
        for (Object tile : tiles) {
            names.add(tileLabel(session, locale, tile.toString()));
        }
        return String.join(" ", names);
    }

    private static ItemStack tileItem(MahjongTableSession session, MahjongTile tile, boolean faceDown, Component name) {
        ItemStack customItem = session.plugin().craftEngine().resolveTileItem(session.currentVariant(), tile, faceDown);
        if (customItem != null) {
            ItemMeta customMeta = customItem.getItemMeta();
            customMeta.displayName(name.colorIfAbsent(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            customItem.setItemMeta(customMeta);
            return customItem;
        }
        String path = faceDown ? "mahjong_tile/back" : tile.itemModelPath();
        ItemStack itemStack = new ItemStack(Material.PAPER);
        ItemMeta meta = itemStack.getItemMeta();
        PaperCompatibility.applyItemModel(meta, new NamespacedKey("mahjongcraft", path));
        meta.displayName(name.colorIfAbsent(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private static MahjongTile toDisplayTile(String name) {
        return MahjongTile.valueOf(name);
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

    private static String tileLabel(MahjongTableSession session, Locale locale, String tileName) {
        String normalized = tileName.toLowerCase(Locale.ROOT);
        String key = "tile." + normalized;
        return session.plugin().messages().contains(locale, key)
            ? session.plugin().messages().plain(locale, key)
            : normalized;
    }

    private static String meldPrefix(MahjongTableSession session, Locale locale, boolean open) {
        return session.plugin().messages().plain(locale, open ? "ui.meld_open" : "ui.meld_closed");
    }

    private static String rankShiftLabel(MahjongTableSession session, Locale locale, String rankShift) {
        return switch (rankShift) {
            case "UP" -> session.plugin().messages().plain(locale, "ui.rank_shift.up");
            case "DOWN" -> session.plugin().messages().plain(locale, "ui.rank_shift.down");
            default -> rankShift;
        };
    }

    private static TableFinalStanding finalStanding(MahjongTableSession session, String stringUuid) {
        try {
            UUID uuid = UUID.fromString(stringUuid);
            return session.finalStandings().stream()
                .filter(standing -> standing.playerId().equals(uuid))
                .findFirst()
                .orElse(null);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String formatGameScore(Locale locale, double score) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(score);
    }

    private static String resolutionTitleLabel(MahjongTableSession session, Locale locale, String title) {
        return translatedLabel(session, locale, "resolution", title);
    }

    private static String drawLabel(MahjongTableSession session, Locale locale, String drawType) {
        return translatedLabel(session, locale, "draw", drawType);
    }

    private static String yakuLabel(MahjongTableSession session, Locale locale, String yaku) {
        return translatedLabel(session, locale, "yaku", yaku);
    }

    private static String fanLabel(MahjongTableSession session, Locale locale, String fan) {
        // GB/Sichuan fan entries may carry a multiplier suffix such as "GEN x2"; translate
        // the fan name and re-attach the original count suffix when present.
        int suffixIndex = fan.lastIndexOf(" x");
        if (suffixIndex > 0 && fan.substring(suffixIndex + 2).chars().allMatch(Character::isDigit) && !fan.substring(suffixIndex + 2).isEmpty()) {
            String name = fan.substring(0, suffixIndex);
            String suffix = fan.substring(suffixIndex);
            return translatedLabel(session, locale, "fan", name) + suffix;
        }
        return translatedLabel(session, locale, "fan", fan);
    }

    private static String yakumanLabel(MahjongTableSession session, Locale locale, String yaku) {
        return translatedLabel(session, locale, "yakuman", yaku);
    }

    private static String doubleYakumanLabel(MahjongTableSession session, Locale locale, String yaku) {
        return translatedLabel(session, locale, "double_yakuman", yaku);
    }

    private static String translatedLabel(MahjongTableSession session, Locale locale, String prefix, String rawValue) {
        String normalized = rawValue.toLowerCase(Locale.ROOT);
        String key = prefix + "." + normalized;
        return session.plugin().messages().contains(locale, key)
            ? session.plugin().messages().plain(locale, key)
            : rawValue;
    }

    private static final class SettlementHolder implements InventoryHolder {
        private final String tableId;
        private Inventory inventory;

        private SettlementHolder(String tableId) {
            this.tableId = tableId;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }

        @SuppressWarnings("unused")
        public String tableId() {
            return this.tableId;
        }
    }
}
