package top.ellan.mahjong.plugin.command.handler;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import top.ellan.mahjong.spi.RuleAction;

/** Stable opaque action encoding shared with the official Sichuan rule pack. */
final class SichuanRefereeProtocol {
    private SichuanRefereeProtocol() {}

    static RuleAction ruling(int seat, String ruling) {
        requireSeat(seat);
        String normalized = ruling.toUpperCase(Locale.ROOT);
        byte[] id = normalized.getBytes(StandardCharsets.US_ASCII);
        if (id.length == 0 || id.length > 64) {
            throw new IllegalArgumentException("Invalid referee ruling identifier");
        }
        byte[] payload = new byte[id.length + 1];
        payload[0] = (byte) seat;
        System.arraycopy(id, 0, payload, 1, id.length);
        return new RuleAction("referee.ruling", payload);
    }

    static RuleAction resolveDisqualification(int seat, boolean confirmed) {
        requireSeat(seat);
        return new RuleAction(
                "referee.resolve_disqualification",
                new byte[] {(byte) seat, confirmed ? (byte) 1 : 0});
    }

    static RuleAction resume(int seat) {
        requireSeat(seat);
        return new RuleAction("referee.resume", new byte[] {(byte) seat});
    }

    private static void requireSeat(int seat) {
        if (seat < 0 || seat > 3) {
            throw new IllegalArgumentException("Seat must be east, south, west or north");
        }
    }
}
