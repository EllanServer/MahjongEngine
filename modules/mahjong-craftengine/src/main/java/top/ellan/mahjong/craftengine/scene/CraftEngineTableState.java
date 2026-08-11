package top.ellan.mahjong.craftengine.scene;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.ellan.mahjong.application.interaction.InteractionRouteBinding;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;

/** Monitor-protected desired/actual state for exactly one table. */
final class CraftEngineTableState {
    final Map<SceneNodeId, SceneNode> desired = new LinkedHashMap<>();
    final Map<SceneNodeId, SceneNode> actual = new LinkedHashMap<>();
    final Set<SceneNodeId> dirty = new LinkedHashSet<>();
    final Set<SceneNodeId> forced = new LinkedHashSet<>();
    final Set<SceneNodeId> inFlight = new LinkedHashSet<>();
    List<InteractionRouteBinding> bindings = List.of();
    long desiredRevision = -1;
    long transientGeneration = -1;
    boolean regionQueued;
    boolean failed;
    boolean closed;
    boolean bindingsInstalled;
    long applyEpoch;
    long tickStamp;
    int tickMutations;
}
