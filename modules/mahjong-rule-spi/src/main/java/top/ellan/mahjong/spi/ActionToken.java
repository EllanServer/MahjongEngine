package top.ellan.mahjong.spi;

import java.util.Objects;
import java.util.UUID;

/** Unforgeable, actor- and revision-bound authority to submit one legal action. */
public record ActionToken(UUID value, PlayerId actor, long revision) {
    public ActionToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(actor, "actor");
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
    }
}
