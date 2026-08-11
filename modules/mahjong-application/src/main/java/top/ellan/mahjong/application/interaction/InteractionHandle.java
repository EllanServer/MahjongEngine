package top.ellan.mahjong.application.interaction;

import java.util.Objects;
import java.util.UUID;

/** Opaque CraftEngine interaction identity; no command string parsing is involved. */
public record InteractionHandle(UUID value) {
    public InteractionHandle {
        Objects.requireNonNull(value, "value");
    }
}
