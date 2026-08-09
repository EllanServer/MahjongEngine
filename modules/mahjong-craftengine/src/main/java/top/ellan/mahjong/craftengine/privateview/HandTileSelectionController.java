package top.ellan.mahjong.craftengine.privateview;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.interaction.HandTileSelectionPort;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Owns one viewer's ephemeral raised-tile selection without touching rule state. */
final class HandTileSelectionController implements HandTileSelectionPort {
    private final ConcurrentHashMap<PlayerId, SelectedTile> selections =
            new ConcurrentHashMap<>();
    private final PlayerRegionTaskScheduler tasks;
    private final HandTileMover mover;
    private final double raise;

    HandTileSelectionController(
            PlayerRegionTaskScheduler tasks,
            HandTileMover mover,
            double raise) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.mover = Objects.requireNonNull(mover, "mover");
        if (!Double.isFinite(raise) || raise < 0.0D) {
            throw new IllegalArgumentException("raise must be finite and non-negative");
        }
        this.raise = raise;
    }

    @Override
    public void showSelection(
            TableId tableId,
            PlayerId playerId,
            Optional<TileInstanceId> selectedTile) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(selectedTile, "selectedTile");
        SelectedTile next =
                selectedTile.map(tile -> new SelectedTile(tableId, tile)).orElse(null);
        SelectionChange change = update(playerId, tableId, next);
        if (!change.changed()) {
            return;
        }
        SelectedTile previous = change.previous();
        Player player = Bukkit.getPlayer(playerId.value());
        if (player == null) {
            return;
        }
        tasks.execute(
                player,
                () -> {
                    if (previous != null) {
                        mover.move(
                                player,
                                previous.tableId(),
                                playerId,
                                previous.tileInstanceId());
                    }
                    if (next != null) {
                        mover.move(player, tableId, playerId, next.tileInstanceId());
                    }
                });
    }

    SceneTransform presentedTransform(
            TableId tableId,
            PlayerId viewer,
            PrivateItemNode item) {
        SelectedTile selected = selections.get(viewer);
        if (selected == null
                || !selected.tableId().equals(tableId)
                || !item.tileInstanceId().equals(selected.tileInstanceId())
                || raise == 0.0D) {
            return item.transform();
        }
        SceneTransform base = item.transform();
        return new SceneTransform(
                base.x(),
                base.y() + raise,
                base.z(),
                base.yawDegrees(),
                base.pitchDegrees(),
                base.rollDegrees(),
                base.scale());
    }

    void onQuit(PlayerId playerId) {
        selections.remove(playerId);
    }

    void clear() {
        selections.clear();
    }

    private SelectionChange update(
            PlayerId playerId,
            TableId tableId,
            SelectedTile next) {
        while (true) {
            SelectedTile previous = selections.get(playerId);
            if (next == null) {
                if (previous == null || !previous.tableId().equals(tableId)) {
                    return new SelectionChange(previous, false);
                }
                if (selections.remove(playerId, previous)) {
                    return new SelectionChange(previous, true);
                }
            } else if (previous == null) {
                if (selections.putIfAbsent(playerId, next) == null) {
                    return new SelectionChange(null, true);
                }
            } else if (previous.equals(next)) {
                return new SelectionChange(previous, false);
            } else if (selections.replace(playerId, previous, next)) {
                return new SelectionChange(previous, true);
            }
        }
    }

    @FunctionalInterface
    interface HandTileMover {
        void move(
                Player player,
                TableId tableId,
                PlayerId viewer,
                TileInstanceId tileInstanceId);
    }

    private record SelectedTile(
            TableId tableId,
            TileInstanceId tileInstanceId) {}

    private record SelectionChange(SelectedTile previous, boolean changed) {}
}
