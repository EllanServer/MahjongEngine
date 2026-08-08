package top.ellan.mahjong.domain;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of a physical or headless table. */
public record TableId(UUID value) implements Comparable<TableId> {
    public TableId {
        Objects.requireNonNull(value, "value");
    }

    public static TableId random() {
        return new TableId(UUID.randomUUID());
    }

    public static TableId parse(String value) {
        return new TableId(UUID.fromString(value));
    }

    @Override
    public int compareTo(TableId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
