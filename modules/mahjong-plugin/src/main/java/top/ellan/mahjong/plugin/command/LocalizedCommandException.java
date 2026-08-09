package top.ellan.mahjong.plugin.command;

import java.util.Objects;

/** Expected command rejection carrying a client translation key. */
public final class LocalizedCommandException extends IllegalArgumentException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final transient CommandMessage reply;

    public LocalizedCommandException(CommandMessage reply) {
        super(Objects.requireNonNull(reply, "reply").consoleText());
        this.reply = reply;
    }

    public CommandMessage reply() {
        return reply;
    }
}
