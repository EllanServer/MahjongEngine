package top.ellan.mahjong.craftengine.privateview;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Immutable identity and spawn location of one viewer-scoped CE packet entity. */
abstract class ClientDisplay {
    private final CraftEngineClientDisplayGateway gateway;
    private final Location location;
    private final int entityId;
    private final UUID uuid = UUID.randomUUID();

    ClientDisplay(
            CraftEngineClientDisplayGateway gateway,
            Location location,
            int entityId) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.location = Objects.requireNonNull(location, "location").clone();
        this.entityId = entityId;
    }

    final int entityId() {
        return entityId;
    }

    final Location location() {
        return location;
    }

    final UUID uuid() {
        return uuid;
    }

    final CraftEngineClientDisplayGateway gateway() {
        return gateway;
    }

    abstract void spawn(Player player);

    final void destroy(Player player) {
        gateway.destroy(player, this);
    }
}
