package top.ellan.mahjong.craftengine.privateview;

import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Viewer-scoped text display encoded and delivered through CraftEngine's network layer. */
final class ClientTextDisplay extends ClientDisplay {
    private String json = "{\"text\":\"\"}";
    private int rgba = 0x40000000;

    ClientTextDisplay(
            CraftEngineClientDisplayGateway gateway,
            Location location,
            int entityId) {
        super(gateway, location, entityId);
    }

    void name(String json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    void rgba(int red, int green, int blue, int alpha) {
        requireColor(red, "red");
        requireColor(green, "green");
        requireColor(blue, "blue");
        requireColor(alpha, "alpha");
        rgba = alpha << 24 | red << 16 | green << 8 | blue;
    }

    @Override
    void spawn(Player player) {
        gateway().sendPackets(
                player,
                List.of(
                        gateway().addPacket(this, gateway().textDisplayType()),
                        gateway().textMetadataPacket(entityId(), json, rgba)));
    }

    private static void requireColor(int value, String channel) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(channel + " must be between 0 and 255");
        }
    }
}
