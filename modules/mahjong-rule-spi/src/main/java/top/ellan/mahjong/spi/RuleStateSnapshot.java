package top.ellan.mahjong.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Provider-owned, versioned binary snapshot. Java native serialization is forbidden. */
public record RuleStateSnapshot(int schemaVersion, long sequence, byte[] payload, String sha256) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    public RuleStateSnapshot {
        if (schemaVersion < 1 || sequence < 0) {
            throw new IllegalArgumentException("Invalid snapshot schema or sequence");
        }
        payload = Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Rule snapshot exceeds 8 MiB");
        }
        payload = payload.clone();
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid snapshot SHA-256");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof RuleStateSnapshot snapshot
                        && schemaVersion == snapshot.schemaVersion
                        && sequence == snapshot.sequence
                        && sha256.equals(snapshot.sha256)
                        && java.util.Arrays.equals(payload, snapshot.payload);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(schemaVersion);
        result = 31 * result + Long.hashCode(sequence);
        result = 31 * result + java.util.Arrays.hashCode(payload);
        return 31 * result + sha256.hashCode();
    }
}
