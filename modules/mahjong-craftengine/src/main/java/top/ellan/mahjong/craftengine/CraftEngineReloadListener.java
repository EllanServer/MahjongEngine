package top.ellan.mahjong.craftengine;

import java.util.Objects;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Opens scene mutation only after CraftEngine confirms that the installed bundle is live. */
public final class CraftEngineReloadListener implements Listener {
    private final CraftEngineSceneBackend backend;

    public CraftEngineReloadListener(CraftEngineSceneBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReload(CraftEngineReloadEvent event) {
        Objects.requireNonNull(event, "event");
        backend.onCraftEngineReloaded();
    }
}
