package top.ellan.mahjong.domain.match;

import java.util.Objects;
import java.util.Optional;

/** Optional external tournament or league reference. */
public record CompetitionRef(Optional<String> value) {
    public CompetitionRef {
        value = Objects.requireNonNull(value, "value");
        value.ifPresent(
                item -> {
                    if (item.isBlank() || item.length() > 128) {
                        throw new IllegalArgumentException("Invalid competition reference");
                    }
                });
    }

    public static CompetitionRef none() {
        return new CompetitionRef(Optional.empty());
    }
}
