package top.ellan.mahjong.craftengine.privateview;

import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Viewer-scoped item display encoded and delivered through CraftEngine's network layer. */
final class ClientItemDisplay extends ClientDisplay {
    private ItemStack item = new ItemStack(Material.AIR);

    ClientItemDisplay(
            CraftEngineClientDisplayGateway gateway,
            Location location,
            int entityId) {
        super(gateway, location, entityId);
    }

    void item(ItemStack item) {
        this.item = Objects.requireNonNull(item, "item").clone();
    }

    @Override
    void spawn(Player player) {
        gateway().sendPackets(
                player,
                List.of(
                        gateway().addPacket(this, gateway().itemDisplayType()),
                        gateway().itemMetadataPacket(entityId(), item)));
    }
}
