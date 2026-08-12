package top.ellan.mahjong.plugin.gameroom;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.plugin.platform.CraftEnginePlatformRuntime;
import top.ellan.mahjong.plugin.table.LiveTableDirectory;
import top.ellan.mahjong.plugin.table.TableLifecycleCoordinator;

/** Restart-scoped game-room composition with no periodic scan or global player lookup. */
public final class GameRoomRuntime implements AutoCloseable {
    private final PluginConfiguration.GameRoomSettings settings;
    private final GameRoomRegistry registry;
    private final GameRoomSelectionService selections = new GameRoomSelectionService();
    private final GameRoomWandListener wand;
    private final MahjongPaperPlugin plugin;
    private final LocalizedMessageCatalog messages;
    private GameRoomExitController exits;

    public GameRoomRuntime(
            MahjongPaperPlugin plugin,
            PluginConfiguration.GameRoomSettings settings,
            java.util.concurrent.Executor ioExecutor,
            LocalizedMessageCatalog messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Objects.requireNonNull(messages, "messages");
        Path file = plugin.getDataFolder().toPath().resolve(settings.file());
        registry = new GameRoomRegistry(file, ioExecutor);
        wand = new GameRoomWandListener(plugin, selections, messages);
        if (settings.enabled()) {
            plugin.getServer().getPluginManager().registerEvents(wand, plugin);
        }
    }

    public void load() {
        registry.load();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public PluginConfiguration.GameRoomSettings settings() {
        return settings;
    }

    public GameRoomRegistry registry() {
        return registry;
    }

    public Optional<GameRoom> roomAt(Location location) {
        return enabled() ? registry.roomAt(location) : Optional.empty();
    }

    public CompletionStage<CreateResult> create(Player player, String rawId, String rawName) {
        Objects.requireNonNull(player, "player");
        String id = GameRoom.normalizeId(rawId);
        String name = rawName == null || rawName.isBlank() ? id : rawName.trim();
        GameRoomSelectionService.Selection selection = selections.selection(player.getUniqueId());
        GameRoom room =
                selection != null && selection.complete()
                        ? fromSelection(id, name, selection, player.getUniqueId())
                        : fromCenter(id, name, player.getLocation(), player.getUniqueId());
        return registry.create(room)
                .thenApply(
                        created -> {
                            if (created) {
                                selections.clear(player.getUniqueId());
                            }
                            return new CreateResult(room, created);
                        });
    }

    public CompletionStage<Boolean> delete(String id) {
        return registry.delete(id);
    }

    public org.bukkit.inventory.ItemStack createWand(java.util.Locale locale) {
        return wand.createWand(locale);
    }

    public synchronized void bindMatchBoundary(
            LiveTableDirectory liveTables,
            CraftEnginePlatformRuntime platform,
            TableLifecycleCoordinator lifecycle,
            TaskScheduler scheduler) {
        if (exits != null) {
            throw new IllegalStateException("Game-room match boundary already bound");
        }
        if (!settings.enabled()) {
            return;
        }
        exits =
                new GameRoomExitController(
                        plugin,
                        settings,
                        registry,
                        liveTables,
                        platform,
                        lifecycle,
                        scheduler,
                        messages);
        plugin.getServer().getPluginManager().registerEvents(exits, plugin);
    }

    /** Composes startup recovery without inspecting players outside the recovered match roster. */
    public Consumer<StartedRulePackMatch> recoveryBoundary(
            Consumer<StartedRulePackMatch> presenceReconciliation) {
        Objects.requireNonNull(presenceReconciliation, "presenceReconciliation");
        return match -> {
            presenceReconciliation.accept(match);
            GameRoomExitController current;
            synchronized (this) {
                current = exits;
            }
            if (current != null) {
                current.reconcileRecoveredMatch(match);
            }
        };
    }

    private GameRoom fromSelection(
            String id,
            String name,
            GameRoomSelectionService.Selection selection,
            UUID ownerId) {
        Location first = selection.first();
        Location second = selection.second();
        World world = Objects.requireNonNull(first.getWorld(), "selection world");
        if (!world.equals(second.getWorld())) {
            throw new IllegalArgumentException("Selection corners must be in the same world");
        }
        return new GameRoom(
                id,
                name,
                world.getUID(),
                world.getName(),
                Math.min(first.getBlockX(), second.getBlockX()),
                Math.min(first.getBlockY(), second.getBlockY()),
                Math.min(first.getBlockZ(), second.getBlockZ()),
                Math.max(first.getBlockX(), second.getBlockX()),
                Math.max(first.getBlockY(), second.getBlockY()),
                Math.max(first.getBlockZ(), second.getBlockZ()),
                ownerId);
    }

    private GameRoom fromCenter(String id, String name, Location center, UUID ownerId) {
        World world = Objects.requireNonNull(center.getWorld(), "player world");
        int minY = Math.max(world.getMinHeight(), center.getBlockY() - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, minY + settings.defaultHeight() - 1);
        int radius = settings.defaultRadius();
        return new GameRoom(
                id,
                name,
                world.getUID(),
                world.getName(),
                center.getBlockX() - radius,
                minY,
                center.getBlockZ() - radius,
                center.getBlockX() + radius,
                maxY,
                center.getBlockZ() + radius,
                ownerId);
    }

    @Override
    public synchronized void close() {
        if (exits != null) {
            exits.close();
            exits = null;
        }
        selections.close();
    }

    public record CreateResult(GameRoom room, boolean created) {
        public CreateResult {
            Objects.requireNonNull(room, "room");
        }
    }
}
