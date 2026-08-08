package top.ellan.mahjong.domain;

import java.util.Objects;
import java.util.UUID;

/** Stable match identity used as the event-log partition key. */
public record MatchId(UUID value) implements Comparable<MatchId> {
    public MatchId {
        Objects.requireNonNull(value, "value");
    }

    public static MatchId random() {
        return new MatchId(UUID.randomUUID());
    }

    public static MatchId parse(String value) {
        return new MatchId(UUID.fromString(value));
    }

    @Override
    public int compareTo(MatchId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
