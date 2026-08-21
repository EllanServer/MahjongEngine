package top.ellan.mahjong.plugin.i18n;

import static net.momirealms.sparrow.message.tag.resolver.Placeholder.styling;
import static net.momirealms.sparrow.message.tag.resolver.Placeholder.unparsed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class SparrowMessageRendererTest {
    @Test
    void rendersAdventureFormatting() {
        Component rendered = SparrowMessageRenderer.render(
                "<gold><bold><title></bold></gold><gray> · </gray><aqua><value></aqua>",
                unparsed("title", "Mahjong"),
                unparsed("value", "East 1"));

        assertEquals(
                "Mahjong · East 1",
                PlainTextComponentSerializer.plainText().serialize(rendered));
    }

    @Test
    void stylingPlaceholderAppliesNavigationEventsWithoutStringInjection() {
        var expectedClick = ClickEvent.runCommand("/mahjong help 2");
        var expectedHover = HoverEvent.showText(Component.text("/mahjong help 2"));
        Component rendered = SparrowMessageRenderer.render(
                "<navigate><dark_gray>[</dark_gray><yellow><label></yellow>"
                        + "<dark_gray>]</dark_gray></navigate>",
                styling("navigate", expectedClick, expectedHover),
                unparsed("label", "Next"));

        assertEquals("[Next]", PlainTextComponentSerializer.plainText().serialize(rendered));
        ArrayDeque<Component> remaining = new ArrayDeque<>();
        remaining.add(rendered);
        boolean foundClick = false;
        boolean foundHover = false;
        while (!remaining.isEmpty()) {
            Component component = remaining.removeFirst();
            foundClick |= expectedClick.equals(component.clickEvent());
            foundHover |= expectedHover.equals(component.hoverEvent());
            remaining.addAll(component.children());
        }
        assertTrue(foundClick);
        assertTrue(foundHover);
    }

    @Test
    void unparsedPlaceholderCannotInjectInteractiveTags() {
        String hostile = "<click:run_command:'/op @s'>click me</click>";
        Component rendered = SparrowMessageRenderer.render(
                "<yellow><value></yellow>", unparsed("value", hostile));

        assertEquals(hostile, PlainTextComponentSerializer.plainText().serialize(rendered));
        ArrayDeque<Component> remaining = new ArrayDeque<>();
        remaining.add(rendered);
        while (!remaining.isEmpty()) {
            Component component = remaining.removeFirst();
            assertNull(component.clickEvent());
            assertNull(component.hoverEvent());
            remaining.addAll(component.children());
        }
    }
}
