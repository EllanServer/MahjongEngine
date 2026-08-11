package top.ellan.mahjong.craftengine.port;

import java.util.Locale;

/** Resolves player-visible text on the server so rendering does not depend on a client pack. */
@FunctionalInterface
public interface PlayerTextResolver {
    String resolve(Locale locale, String translationKey, String fallback);

    static PlayerTextResolver fallbackOnly() {
        return (locale, translationKey, fallback) -> fallback;
    }
}
