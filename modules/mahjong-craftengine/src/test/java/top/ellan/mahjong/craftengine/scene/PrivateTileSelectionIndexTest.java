package top.ellan.mahjong.craftengine.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.PrivateFurnitureNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

class PrivateTileSelectionIndexTest {
    private static final PlayerId VIEWER = new PlayerId(UUID.randomUUID());
    private static final SceneTransform TRANSFORM =
            new SceneTransform(0, 1, 0, 0, 0, 0, 1);

    @Test
    void selectionsFromDifferentTablesCannotClearOneAnother() {
        PrivateTileSelectionIndex selections = new PrivateTileSelectionIndex();
        TableId firstTable = TableId.random();
        TableId secondTable = TableId.random();
        PrivateFurnitureNode first = tile(1);
        PrivateFurnitureNode second = tile(2);
        ConditionalFurnitureKey firstKey =
                new ConditionalFurnitureKey(firstTable, first.id());
        ConditionalFurnitureKey secondKey =
                new ConditionalFurnitureKey(secondTable, second.id());
        selections.index(firstKey, first, VIEWER);
        selections.index(secondKey, second, VIEWER);
        ArrayList<ConditionalFurnitureKey> refreshed = new ArrayList<>();

        selections.showSelection(
                firstTable, VIEWER, Optional.of(first.tileInstanceId()), refreshed::add);
        selections.showSelection(
                secondTable, VIEWER, Optional.of(second.tileInstanceId()), refreshed::add);
        selections.showSelection(firstTable, VIEWER, Optional.empty(), refreshed::add);

        assertEquals("ground", selections.variant(firstTable, VIEWER, first));
        assertEquals("selected", selections.variant(secondTable, VIEWER, second));
        assertEquals(
                java.util.List.of(firstKey, secondKey, firstKey),
                refreshed);
    }

    private static PrivateFurnitureNode tile(long id) {
        TileInstanceId tile = new TileInstanceId(id);
        return new PrivateFurnitureNode(
                new SceneNodeId("tile/private/test/" + id),
                SceneVisibility.privateTo(VIEWER),
                tile,
                new TileVisualId("riichi:tile/m1"),
                TRANSFORM);
    }
}
