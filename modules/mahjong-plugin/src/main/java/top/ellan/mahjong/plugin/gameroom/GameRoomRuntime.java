package top.ellan.mahjong.plugin.gameroom;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.config.PluginConfiguration;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;

/** Restart-scoped game-room composition with no periodic scan or global player lookup. */
public final class GameRoomRuntime implements AutoCloseable {
    private final PluginConfiguration.GameRoomSettings settings;
    private final GameRoomRegistry registry;
    private final GameRoomSelectionService selections = new GameRoomSelectionService();
    private final GameRoomWandListener wand;

    public GameRoomRuntime(
            MahjongPaperPlugin plugin,
            PluginConfiguration.GameRoomSettings settings,
            java.util.concurrent.Executor ioExecutor,
            LocalizedMessageCatalog messages) {
        Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
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
    public void close() {
        selections.close();
    }

    public record CreateResult(GameRoom room, boolean created) {
        public CreateResult {
            Objects.requireNonNull(room, "room");
        }
    }
}
