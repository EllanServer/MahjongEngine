package top.ellan.mahjong.craftengine.privateview;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.mahjong.craftengine.privateview.HudTextFormatter.HudPresentation;
import top.ellan.mahjong.spi.PlayerId;

/** Active BossBars indexed only by viewers who currently belong to a projected table. */
final class ViewerHudBars {
    private final ConcurrentHashMap<PlayerId, ActiveHud> active = new ConcurrentHashMap<>();

    void refresh(Player player, PlayerId viewer, HudPresentation presentation) {
        if (!presentation.visible()) {
            hide(player, viewer);
            return;
        }
        ActiveHud current = active.get(viewer);
        if (current == null) {
            BossBar bar = BossBar.bossBar(
                    presentation.title(),
                    presentation.progress(),
                    presentation.color(),
                    BossBar.Overlay.PROGRESS);
            ActiveHud installed = new ActiveHud(bar, presentation);
            ActiveHud raced = active.putIfAbsent(viewer, installed);
            if (raced == null) {
                player.showBossBar(bar);
                return;
            }
            current = raced;
        }
        if (current.presentation().equals(presentation)) {
            return;
        }
        current.bar().name(presentation.title());
        current.bar().progress(presentation.progress());
        current.bar().color(presentation.color());
        active.replace(viewer, current, new ActiveHud(current.bar(), presentation));
    }

    void hide(Player player, PlayerId viewer) {
        ActiveHud removed = active.remove(viewer);
        if (removed != null) {
            player.hideBossBar(removed.bar());
        }
    }

    void forget(PlayerId viewer) {
        active.remove(viewer);
    }

    void close(PlayerRegionTaskScheduler tasks) {
        Map<PlayerId, ActiveHud> snapshot = Map.copyOf(active);
        active.clear();
        snapshot.forEach((viewer, hud) -> {
            Player player = Bukkit.getPlayer(viewer.value());
            if (player != null) {
                tasks.execute(player, () -> player.hideBossBar(hud.bar()));
            }
        });
    }

    private record ActiveHud(BossBar bar, HudPresentation presentation) {}
}
