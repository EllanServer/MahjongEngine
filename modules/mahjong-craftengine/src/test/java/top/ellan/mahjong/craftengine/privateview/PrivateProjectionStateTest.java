package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

class PrivateProjectionStateTest {
    private static final PlayerId VIEWER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final PlayerId OTHER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final SceneTransform TRANSFORM =
            new SceneTransform(0, 1, 0, 0, 0, 0, 1);

    @Test
    void indexesAndRemovesPrivateTilesWithoutScanningViewerState() {
        PrivateProjectionState state = new PrivateProjectionState();
        TableId tableId = TableId.random();
        TileInstanceId tileId = new TileInstanceId(17);
        SceneNodeId nodeId = new SceneNodeId("private/tile/17");

        PrivateProjectionState.UpsertedNode upserted = state.upsert(
                tableId,
                new PrivateItemNode(
                        nodeId,
                        SceneVisibility.privateTo(VIEWER),
                        tileId,
                        new TileVisualId("riichi:tile/m5"),
                        TRANSFORM));

        assertEquals(upserted.key(), state.handTile(tableId, VIEWER, tileId));
        assertEquals(1, state.desiredNodes(VIEWER).size());
        assertTrue(state.remove(tableId, nodeId).isPresent());
        assertNull(state.handTile(tableId, VIEWER, tileId));
        assertTrue(state.desiredNodes(VIEWER).isEmpty());
    }

    @Test
    void cameraLookupTracksReplacementAndRemoval() {
        PrivateProjectionState state = new PrivateProjectionState();
        TableId tableId = TableId.random();
        SceneNodeId nodeId = new SceneNodeId("private/camera");
        state.upsert(
                tableId,
                new CameraNode(
                        nodeId,
                        SceneVisibility.privateTo(VIEWER),
                        TRANSFORM,
                        false));

        assertTrue(state.cameraNode(tableId, VIEWER).isPresent());
        state.remove(tableId, nodeId);
        assertFalse(state.cameraNode(tableId, VIEWER).isPresent());
    }

    @Test
    void stableNodeCannotLeakAcrossPrivateViewers() {
        PrivateProjectionState state = new PrivateProjectionState();
        TableId tableId = TableId.random();
        SceneNodeId nodeId = new SceneNodeId("private/tile/shared-id");
        state.upsert(tableId, tile(nodeId, VIEWER, 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> state.upsert(tableId, tile(nodeId, OTHER, 2)));
    }

    private static PrivateItemNode tile(SceneNodeId nodeId, PlayerId viewer, long tileId) {
        return new PrivateItemNode(
                nodeId,
                SceneVisibility.privateTo(viewer),
                new TileInstanceId(tileId),
                new TileVisualId("mcr:tile/m1"),
                TRANSFORM);
    }
}
