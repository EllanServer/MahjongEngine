package top.ellan.mahjong.craftengine.scene;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.PrivateFurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Thread-safe viewer/table selection state; different tables can never clear one another. */
final class PrivateTileSelectionIndex {
    private final ConcurrentHashMap<ViewerTileKey, ConditionalFurnitureKey> furnitureByTile =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ViewerTableKey, TileInstanceId> selectedByTable =
            new ConcurrentHashMap<>();

    void showSelection(
            TableId tableId,
            PlayerId viewer,
            Optional<TileInstanceId> selectedTile,
            Consumer<ConditionalFurnitureKey> refresh) {
        ViewerTableKey table = new ViewerTableKey(tableId, viewer);
        TileInstanceId next = selectedTile.orElse(null);
        TileInstanceId previous = update(table, next);
        if (Objects.equals(previous, next)) {
            return;
        }
        refresh(tableId, viewer, previous, refresh);
        refresh(tableId, viewer, next, refresh);
    }

    void index(
            ConditionalFurnitureKey key, SceneNode node, PlayerId viewer) {
        if (node instanceof PrivateFurnitureNode tile) {
            furnitureByTile.put(
                    new ViewerTileKey(key.tableId(), viewer, tile.tileInstanceId()), key);
        }
    }

    void unindex(ConditionalFurnitureKey key, SceneNode node) {
        if (!(node instanceof PrivateFurnitureNode tile)) {
            return;
        }
        tile.visibility().singleViewer().ifPresent(viewer -> furnitureByTile.remove(
                new ViewerTileKey(key.tableId(), viewer, tile.tileInstanceId()), key));
    }

    String variant(TableId tableId, PlayerId viewer, PrivateFurnitureNode tile) {
        TileInstanceId selected = selectedByTable.get(new ViewerTableKey(tableId, viewer));
        return tile.tileInstanceId().equals(selected) ? "selected" : "ground";
    }

    void clear() {
        furnitureByTile.clear();
        selectedByTable.clear();
    }

    private TileInstanceId update(ViewerTableKey key, TileInstanceId next) {
        while (true) {
            TileInstanceId previous = selectedByTable.get(key);
            if (next == null) {
                if (previous == null || selectedByTable.remove(key, previous)) {
                    return previous;
                }
            } else if (previous == null) {
                if (selectedByTable.putIfAbsent(key, next) == null) {
                    return null;
                }
            } else if (previous.equals(next)) {
                return previous;
            } else if (selectedByTable.replace(key, previous, next)) {
                return previous;
            }
        }
    }

    private void refresh(
            TableId tableId,
            PlayerId viewer,
            TileInstanceId tile,
            Consumer<ConditionalFurnitureKey> refresh) {
        if (tile == null) {
            return;
        }
        ConditionalFurnitureKey key =
                furnitureByTile.get(new ViewerTileKey(tableId, viewer, tile));
        if (key != null) {
            refresh.accept(key);
        }
    }

    private record ViewerTableKey(TableId tableId, PlayerId viewer) {}

    private record ViewerTileKey(
            TableId tableId, PlayerId viewer, TileInstanceId tileInstanceId) {}
}
