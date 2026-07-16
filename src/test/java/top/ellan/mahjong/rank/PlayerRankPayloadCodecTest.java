package top.ellan.mahjong.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.db.MahjongSoulRankRules;
import top.ellan.mahjong.model.MahjongVariant;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerRankPayloadCodecTest {
    private final PlayerRankPayloadCodec codec = new PlayerRankPayloadCodec();

    @Test
    void roundTripsEveryProfileFieldAndMigrationMarker() throws Exception {
        UUID playerId = UUID.randomUUID();
        Map<MahjongVariant, MahjongSoulRankProfile> profiles = profiles(playerId);

        PlayerRankPayloadCodec.Decoded decoded = this.codec.decode(playerId, this.codec.encode(playerId, profiles, true));

        assertTrue(decoded.migrated());
        assertEquals(profiles, decoded.profiles());
        assertEquals(27, decoded.profiles().get(MahjongVariant.RIICHI).level());
    }

    @Test
    void preservesAnExplicitUnmigratedMarker() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerRankPayloadCodec.Decoded decoded = this.codec.decode(playerId, this.codec.encode(playerId, profiles(playerId), false));
        assertFalse(decoded.migrated());
    }

    @Test
    void rejectsCorruptionAndNeverTreatsItAsMissing() {
        UUID playerId = UUID.randomUUID();
        byte[] payload = this.codec.encode(playerId, profiles(playerId), true);
        payload[0] ^= 0x7F;
        assertThrows(IOException.class, () -> this.codec.decode(playerId, payload));
        assertThrows(IOException.class, () -> this.codec.decode(playerId, new byte[PlayerRankPayloadCodec.MAX_PAYLOAD_BYTES + 1]));
    }

    private static Map<MahjongVariant, MahjongSoulRankProfile> profiles(UUID playerId) {
        Map<MahjongVariant, MahjongSoulRankProfile> profiles = new EnumMap<>(MahjongVariant.class);
        for (MahjongVariant mode : MahjongVariant.values()) {
            profiles.put(mode, MahjongSoulRankProfile.defaultProfile(playerId, "Player"));
        }
        profiles.put(
            MahjongVariant.RIICHI,
            new MahjongSoulRankProfile(playerId, "雀士", MahjongSoulRankRules.Tier.CELESTIAL, 27, 155, 10, 2, 3, 4, 1)
        );
        return profiles;
    }
}
