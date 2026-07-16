package top.ellan.mahjong.table.presentation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.table.core.TableSessionContext;

final class TableDiceAnimationCoordinatorTest {
    @Test
    void resultLabelUsesTheNonDeprecatedDefaultBackground() throws ReflectiveOperationException {
        TableSessionContext session = mock(TableSessionContext.class);
        TableDiceAnimationCoordinator coordinator = new TableDiceAnimationCoordinator(session);
        World world = mock(World.class);
        TextDisplay label = mock(TextDisplay.class);
        Location center = new Location(world, 10.0D, 64.0D, 20.0D);
        Component text = Component.text("3 + 4 = 7");
        when(world.spawn(any(Location.class), eq(TextDisplay.class))).thenReturn(label);
        setField(coordinator, "world", world);
        setField(coordinator, "center", center);

        Method spawnResultLabel = TableDiceAnimationCoordinator.class.getDeclaredMethod(
            "spawnResultLabel",
            Component.class
        );
        spawnResultLabel.setAccessible(true);
        TextDisplay result = (TextDisplay) spawnResultLabel.invoke(coordinator, text);

        assertSame(label, result);
        verify(label).setPersistent(false);
        verify(label).text(text);
        verify(label).setSeeThrough(false);
        verify(label).setShadowed(true);
        verify(label).setDefaultBackground(true);
        verify(label).setBillboard(Display.Billboard.CENTER);
        verify(label).setLineWidth(180);
        verify(label).setViewRange(32.0F);
        verify(label).setBrightness(new Display.Brightness(15, 15));
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
