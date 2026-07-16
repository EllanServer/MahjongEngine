package top.ellan.mahjong.render.display;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.Color;
import org.bukkit.entity.TextDisplay;

/**
 * Version-neutral access to the text-display background color.
 *
 * <p>Paper 1.20.1 temporarily marked this API as deprecated while retaining the exact same public
 * signature that current Paper exposes without deprecation. A cached capability lookup keeps the
 * minimum-version compiler from binding to that temporary annotation while preserving the exact
 * ARGB behavior on every supported server.
 */
public final class TextDisplayBackgrounds {
    private static final MethodHandle SET_BACKGROUND_COLOR = findSetter();

    private TextDisplayBackgrounds() {
    }

    public static void set(TextDisplay display, Color color) {
        try {
            SET_BACKGROUND_COLOR.invokeExact(display, color);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("TextDisplay background-color invocation failed", throwable);
        }
    }

    private static MethodHandle findSetter() {
        try {
            return MethodHandles.publicLookup().findVirtual(
                TextDisplay.class,
                "setBackgroundColor",
                MethodType.methodType(void.class, Color.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
