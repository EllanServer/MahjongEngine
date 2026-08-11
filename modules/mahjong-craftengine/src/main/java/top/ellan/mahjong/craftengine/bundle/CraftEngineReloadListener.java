package top.ellan.mahjong.craftengine.bundle;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import top.ellan.mahjong.craftengine.scene.CraftEngineSceneBackend;

/** Opens scene mutation only after CraftEngine confirms that the installed bundle is live. */
public final class CraftEngineReloadListener implements Listener {
    private final CraftEngineSceneBackend backend;
    private final BooleanSupplier bundleInstalled;

    public CraftEngineReloadListener(
            CraftEngineSceneBackend backend, BooleanSupplier bundleInstalled) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.bundleInstalled = Objects.requireNonNull(bundleInstalled, "bundleInstalled");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReload(CraftEngineReloadEvent event) {
        Objects.requireNonNull(event, "event");
        if (bundleInstalled.getAsBoolean()) {
            backend.onCraftEngineReloaded();
        } else {
            backend.onCraftEngineReloadStarted();
        }
    }
}
