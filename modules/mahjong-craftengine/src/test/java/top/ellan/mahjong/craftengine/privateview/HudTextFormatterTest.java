package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.PlayerId;

class HudTextFormatterTest {
    private static final PlayerId VIEWER = new PlayerId(new UUID(0, 1));

    @Test
    void rendersTheV15CompactRoundTurnAndWallHud() {
        Map<String, String> translations = Map.of(
                "mahjongpaper.hud.turn", "当前",
                "mahjongpaper.hud.wall", "牌山",
                "mahjongpaper.tile.east", "东");
        HudTextFormatter formatter = new HudTextFormatter(
                (locale, key, fallback) -> translations.getOrDefault(key, fallback));
        var presentation = formatter.format(
                Locale.SIMPLIFIED_CHINESE,
                java.util.List.of(
                        node("public:roundWind", "EAST"),
                        node("public:handNumber", "1"),
                        node("public:currentSeat", "0"),
                        node("public:wallRemaining", "42"),
                        node("meta:wallCapacity", "136")));

        assertEquals(
                net.kyori.adventure.text.Component.text("东 1", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                        .append(net.kyori.adventure.text.Component.text(
                                " | ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(net.kyori.adventure.text.Component.text(
                                "当前 ", net.kyori.adventure.text.format.NamedTextColor.AQUA))
                        .append(net.kyori.adventure.text.Component.text(
                                "东", net.kyori.adventure.text.format.NamedTextColor.WHITE))
                        .append(net.kyori.adventure.text.Component.text(
                                " | ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .append(net.kyori.adventure.text.Component.text(
                                "牌山 ", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                        .append(net.kyori.adventure.text.Component.text(
                                "42", net.kyori.adventure.text.format.NamedTextColor.WHITE)),
                presentation.title());
        assertEquals(42.0F / 136.0F, presentation.progress(), 0.0001F);
    }

    @Test
    void hidesWhenNoCompactHudStateExists() {
        HudTextFormatter formatter = new HudTextFormatter((locale, key, fallback) -> fallback);
        assertEquals(false, formatter.format(Locale.ENGLISH, java.util.List.of()).visible());
    }

    private static HudNode node(String key, String value) {
        return new HudNode(
                new SceneNodeId(
                        "hud/" + key.replace(':', '/').toLowerCase(Locale.ROOT)),
                SceneVisibility.privateTo(Set.of(VIEWER)),
                key,
                value);
    }
}
