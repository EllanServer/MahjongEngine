package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.node.SceneTransform;
import top.ellan.mahjong.presentation.node.SceneVisibility;
import top.ellan.mahjong.spi.PlayerId;

class PrivateProjectionStateTest {
    private static final PlayerId VIEWER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final PlayerId OTHER =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final SceneTransform TRANSFORM =
            new SceneTransform(0, 1, 0, 0, 0, 0, 1);

    @Test
    void sharedNodeIsIndexedOncePerViewerAndRemovedForAll() {
        PrivateProjectionState state = new PrivateProjectionState();
        TableId tableId = TableId.random();
        SceneNodeId nodeId = new SceneNodeId("hud/shared/phase");

        var upserted = state.upsert(
                tableId,
                new top.ellan.mahjong.presentation.node.HudNode(
                        nodeId,
                        SceneVisibility.privateTo(java.util.Set.of(VIEWER, OTHER)),
                        "phase",
                        "DISCARD"));

        assertEquals(2, upserted.size());
        assertEquals(1, state.desiredNodes(VIEWER).size());
        assertEquals(1, state.desiredNodes(OTHER).size());

        assertEquals(2, state.remove(tableId, nodeId).size());
        assertTrue(state.desiredNodes(VIEWER).isEmpty());
        assertTrue(state.desiredNodes(OTHER).isEmpty());
    }

    @Test
    void shrinkingASharedAudienceForgetsTheDroppedViewer() {
        PrivateProjectionState state = new PrivateProjectionState();
        TableId tableId = TableId.random();
        SceneNodeId nodeId = new SceneNodeId("hud/shared/phase");
        state.upsert(
                tableId,
                new top.ellan.mahjong.presentation.node.HudNode(
                        nodeId,
                        SceneVisibility.privateTo(java.util.Set.of(VIEWER, OTHER)),
                        "phase",
                        "DISCARD"));

        state.upsert(
                tableId,
                new top.ellan.mahjong.presentation.node.HudNode(
                        nodeId,
                        SceneVisibility.privateTo(java.util.Set.of(VIEWER)),
                        "phase",
                        "DRAW"));

        assertEquals(1, state.desiredNodes(VIEWER).size());
        assertTrue(state.desiredNodes(OTHER).isEmpty());
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
        state.upsert(
                tableId,
                new CameraNode(nodeId, SceneVisibility.privateTo(VIEWER), TRANSFORM, false));

        assertThrows(
                IllegalArgumentException.class,
                () -> state.upsert(
                        tableId,
                        new CameraNode(
                                nodeId, SceneVisibility.privateTo(OTHER), TRANSFORM, false)));
    }
}
