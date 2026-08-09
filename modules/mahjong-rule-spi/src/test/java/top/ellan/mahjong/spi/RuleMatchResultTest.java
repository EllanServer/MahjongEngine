package top.ellan.mahjong.spi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleMatchResultTest {
    @Test
    void ownsCanonicalPayloadAndRejectsDuplicatePlayers() {
        PlayerId player = new PlayerId(UUID.randomUUID());
        byte[] payload = new byte[] {1, 2};
        RulePlayerResult first =
                new RulePlayerResult(player, new SeatId(0), 1, 10, 4_000, payload);
        payload[0] = 9;

        assertArrayEquals(new byte[] {1, 2}, first.canonicalPayload());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RuleMatchResult(
                        "test.v1",
                        List.of(
                                first,
                                new RulePlayerResult(
                                        player,
                                        new SeatId(1),
                                        2,
                                        -10,
                                        3_000,
                                        new byte[0]))));
    }
}
