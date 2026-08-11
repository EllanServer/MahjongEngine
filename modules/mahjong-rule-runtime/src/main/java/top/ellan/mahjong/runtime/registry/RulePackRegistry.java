package top.ellan.mahjong.runtime.registry;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import top.ellan.mahjong.spi.RuleId;

/** Verified registry payload. */
public record RulePackRegistry(int formatVersion, Instant generatedAt, List<RulePackRegistryEntry> entries) {
    public RulePackRegistry {
        if (formatVersion < 1 || formatVersion > 2) {
            throw new IllegalArgumentException("Unsupported registry format: " + formatVersion);
        }
        Objects.requireNonNull(generatedAt, "generatedAt");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        HashSet<String> coordinates = new HashSet<>();
        for (RulePackRegistryEntry entry : entries) {
            if (!coordinates.add(entry.ruleId() + ":" + entry.version())) {
                throw new IllegalArgumentException("Duplicate registry coordinate");
            }
        }
    }

    public Optional<RulePackRegistryEntry> find(RuleId ruleId, Optional<String> version) {
        return entries.stream()
                .filter(entry -> entry.ruleId().equals(ruleId))
                .filter(entry -> version.isEmpty() || version.orElseThrow().equals(entry.version()))
                .max(Comparator.comparing(RulePackRegistryEntry::version, VersionOrder.INSTANCE));
    }

    private enum VersionOrder implements Comparator<String> {
        INSTANCE;

        @Override
        public int compare(String left, String right) {
            String[] a = left.split("[.-]");
            String[] b = right.split("[.-]");
            int count = Math.max(a.length, b.length);
            for (int index = 0; index < count; index++) {
                String av = index < a.length ? a[index] : "0";
                String bv = index < b.length ? b[index] : "0";
                int compared;
                if (av.matches("[0-9]+") && bv.matches("[0-9]+")) {
                    compared = new java.math.BigInteger(av).compareTo(new java.math.BigInteger(bv));
                } else {
                    compared = av.compareTo(bv);
                }
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        }
    }
}
