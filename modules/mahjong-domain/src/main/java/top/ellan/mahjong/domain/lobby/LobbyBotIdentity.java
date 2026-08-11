package top.ellan.mahjong.domain.lobby;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Deterministic, persistence-safe identity for an official bot occupying one lobby seat. */
public final class LobbyBotIdentity {
    private static final long NAMESPACE_HIGH = 0x6d61686a6f6e6770L;
    private static final long NAMESPACE_LOW = 0x617065722d626f74L;

    private LobbyBotIdentity() {}

    public static PlayerId forSeat(TableId tableId, SeatId seatId) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(seatId, "seatId");
        UUID table = tableId.value();
        byte[] name = ByteBuffer.allocate(33)
                .putLong(NAMESPACE_HIGH)
                .putLong(NAMESPACE_LOW)
                .putLong(table.getMostSignificantBits())
                .putLong(table.getLeastSignificantBits())
                .put((byte) seatId.value())
                .array();
        return new PlayerId(UUID.nameUUIDFromBytes(name));
    }

    public static boolean occupies(TableLobby lobby, LobbySeat seat) {
        return seat.occupant()
                .filter(forSeat(lobby.tableId(), seat.seatId())::equals)
                .isPresent();
    }
}
