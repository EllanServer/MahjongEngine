package top.ellan.mahjong.rank;

import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.db.MahjongSoulRankRules;
import top.ellan.mahjong.model.MahjongVariant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Versioned and bounded InvSync payload for every per-player rank field. */
public final class PlayerRankPayloadCodec {
    static final int MAGIC = 0x4D4A5250; // MJRP
    static final int VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 4096;
    private static final int MAX_NAME_BYTES = 128;
    private static final int MAX_COUNTER = 1_000_000_000;

    public byte[] encode(UUID owner, Map<MahjongVariant, MahjongSoulRankProfile> profiles, boolean migrated) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(profiles, "profiles");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(384);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeShort(VERSION);
                output.writeBoolean(migrated);
                output.writeByte(MahjongVariant.values().length);
                for (MahjongVariant mode : MahjongVariant.values()) {
                    MahjongSoulRankProfile profile = Objects.requireNonNull(profiles.get(mode), "Missing rank profile for " + mode);
                    this.validateProfile(owner, profile);
                    output.writeByte(mode.ordinal());
                    output.writeLong(profile.playerId().getMostSignificantBits());
                    output.writeLong(profile.playerId().getLeastSignificantBits());
                    byte[] displayName = profile.displayName().getBytes(StandardCharsets.UTF_8);
                    if (displayName.length > MAX_NAME_BYTES) {
                        throw new IllegalArgumentException("Rank display name exceeds " + MAX_NAME_BYTES + " UTF-8 bytes");
                    }
                    output.writeShort(displayName.length);
                    output.write(displayName);
                    output.writeByte(profile.tier().ordinal());
                    output.writeInt(profile.level());
                    output.writeInt(profile.rankPoints());
                    output.writeInt(profile.totalMatches());
                    output.writeInt(profile.firstPlaces());
                    output.writeInt(profile.secondPlaces());
                    output.writeInt(profile.thirdPlaces());
                    output.writeInt(profile.fourthPlaces());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Rank payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
            }
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected in-memory rank serialization failure", exception);
        }
    }

    public Decoded decode(UUID expectedOwner, byte[] payload) throws IOException {
        Objects.requireNonNull(expectedOwner, "expectedOwner");
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Rank payload has an invalid length");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Rank payload magic does not match");
            }
            int version = input.readUnsignedShort();
            if (version != VERSION) {
                throw new IOException("Unsupported rank payload version " + version);
            }
            boolean migrated = input.readBoolean();
            int count = input.readUnsignedByte();
            if (count != MahjongVariant.values().length) {
                throw new IOException("Rank payload mode count is invalid");
            }
            Map<MahjongVariant, MahjongSoulRankProfile> profiles = new EnumMap<>(MahjongVariant.class);
            for (int index = 0; index < count; index++) {
                int modeOrdinal = input.readUnsignedByte();
                if (modeOrdinal >= MahjongVariant.values().length) {
                    throw new IOException("Rank payload contains an unknown mode");
                }
                MahjongVariant mode = MahjongVariant.values()[modeOrdinal];
                if (profiles.containsKey(mode)) {
                    throw new IOException("Rank payload contains a duplicate mode");
                }
                UUID playerId = new UUID(input.readLong(), input.readLong());
                int nameLength = input.readUnsignedShort();
                if (nameLength > MAX_NAME_BYTES) {
                    throw new IOException("Rank payload display name is too long");
                }
                byte[] nameBytes = input.readNBytes(nameLength);
                if (nameBytes.length != nameLength) {
                    throw new EOFException("Rank payload display name is truncated");
                }
                String displayName;
                try {
                    displayName = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(nameBytes))
                        .toString();
                } catch (CharacterCodingException exception) {
                    throw new IOException("Rank payload display name is not valid UTF-8", exception);
                }
                int tierOrdinal = input.readUnsignedByte();
                if (tierOrdinal >= MahjongSoulRankRules.Tier.values().length) {
                    throw new IOException("Rank payload contains an unknown tier");
                }
                MahjongSoulRankProfile profile = new MahjongSoulRankProfile(
                    playerId,
                    displayName,
                    MahjongSoulRankRules.Tier.values()[tierOrdinal],
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    input.readInt()
                );
                try {
                    this.validateProfile(expectedOwner, profile);
                } catch (IllegalArgumentException exception) {
                    throw new IOException(exception.getMessage(), exception);
                }
                profiles.put(mode, profile);
            }
            if (input.available() != 0) {
                throw new IOException("Rank payload contains trailing data");
            }
            return new Decoded(Map.copyOf(profiles), migrated);
        }
    }

    private void validateProfile(UUID expectedOwner, MahjongSoulRankProfile profile) {
        if (!expectedOwner.equals(profile.playerId())) {
            throw new IllegalArgumentException("Rank payload owner does not match player");
        }
        if (profile.displayName() == null || profile.displayName().isBlank()) {
            throw new IllegalArgumentException("Rank display name is blank");
        }
        if (profile.tier() == null
            || profile.level() < 1
            || profile.level() > MAX_COUNTER
            || profile.tier() != MahjongSoulRankRules.Tier.CELESTIAL && profile.level() > 3) {
            throw new IllegalArgumentException("Rank tier or level is invalid");
        }
        if (Math.abs((long) profile.rankPoints()) > MAX_COUNTER) {
            throw new IllegalArgumentException("Rank points are outside the supported range");
        }
        int[] counters = {
            profile.totalMatches(),
            profile.firstPlaces(),
            profile.secondPlaces(),
            profile.thirdPlaces(),
            profile.fourthPlaces()
        };
        for (int counter : counters) {
            if (counter < 0 || counter > MAX_COUNTER) {
                throw new IllegalArgumentException("Rank counter is outside the supported range");
            }
        }
        long placements = (long) profile.firstPlaces() + profile.secondPlaces() + profile.thirdPlaces() + profile.fourthPlaces();
        if (placements > profile.totalMatches()) {
            throw new IllegalArgumentException("Rank placements exceed total matches");
        }
    }

    public record Decoded(Map<MahjongVariant, MahjongSoulRankProfile> profiles, boolean migrated) {
    }
}
