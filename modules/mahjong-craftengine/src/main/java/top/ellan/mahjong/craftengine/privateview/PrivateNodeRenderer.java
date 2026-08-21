package top.ellan.mahjong.craftengine.privateview;

import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.ActiveLabel;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.DesiredNode;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.NodeKey;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.spi.PlayerId;

/** Region-thread renderer for dynamic-argument labels and HUD channels CE cannot express. */
final class PrivateNodeRenderer implements AutoCloseable {
    private final TableAnchorLookup anchors;
    private final PrivateProjectionState state;
    private final PlayerRegionTaskScheduler tasks;
    private final CraftEngineClientDisplayGateway displays;
    private final Predicate<PlayerId> cameraViewing;
    private final PlayerTextResolver messages;
    private final HudTextFormatter hudText;
    private final ViewerHudBars hudBars = new ViewerHudBars();

    PrivateNodeRenderer(
            TableAnchorLookup anchors,
            PrivateProjectionState state,
            PlayerRegionTaskScheduler tasks,
            CraftEngineClientDisplayGateway displays,
            Predicate<PlayerId> cameraViewing,
            PlayerTextResolver messages) {
        this.anchors = anchors;
        this.state = state;
        this.tasks = tasks;
        this.displays = displays;
        this.cameraViewing = cameraViewing;
        this.messages = messages;
        hudText = new HudTextFormatter(messages);
    }

    void apply(Player player, NodeKey key, long expectedGeneration) {
        DesiredNode target = state.desiredNode(key.viewer(), key);
        if (target == null || target.generation() != expectedGeneration || !player.isOnline()) {
            return;
        }
        if (target.node() instanceof CameraNode) {
            return;
        }
        if (target.node() instanceof HudNode) {
            refreshHud(player, key.viewer());
            return;
        }
        if (target.node() instanceof ActionLabelNode label) {
            if (cameraViewing.test(key.viewer())) {
                remove(player, key);
                return;
            }
            applyLabel(player, key, expectedGeneration, label);
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported client-private scene node: " + target.node().getClass());
    }

    void remove(Player player, NodeKey key) {
        ActiveLabel label = state.removeActiveLabel(key);
        if (label != null) {
            label.display().destroy(player);
        }
    }

    void forget(NodeKey key) {
        state.removeActiveLabel(key);
    }

    void refreshHud(Player player, PlayerId viewer) {
        java.util.List<HudNode> nodes = state.desiredNodes(viewer).values().stream()
                .map(DesiredNode::node)
                .filter(HudNode.class::isInstance)
                .map(HudNode.class::cast)
                .toList();
        hudBars.refresh(player, viewer, hudText.format(player.locale(), nodes));
    }

    void hideActionLabels(Player player, PlayerId viewer) {
        for (ActiveLabel label : state.removeActiveLabels(viewer)) {
            label.display().destroy(player);
        }
    }

    void restoreActionLabels(Player player, PlayerId viewer) {
        if (!player.isOnline()) {
            return;
        }
        for (Map.Entry<NodeKey, DesiredNode> entry : state.desiredNodes(viewer).entrySet()) {
            if (entry.getValue().node() instanceof ActionLabelNode) {
                apply(player, entry.getKey(), entry.getValue().generation());
            }
        }
    }

    void onQuit(PlayerId viewer) {
        state.forgetActivity(viewer);
        hudBars.forget(viewer);
    }

    @Override
    public void close() {
        for (PrivateProjectionState.ViewerLabels viewer : state.activeLabelSnapshot()) {
            Player player = Bukkit.getPlayer(viewer.viewer().value());
            if (player != null) {
                for (ActiveLabel label : viewer.labels()) {
                    tasks.execute(player, () -> label.display().destroy(player));
                }
            }
        }
        hudBars.close(tasks);
    }

    private void applyLabel(
            Player player,
            NodeKey key,
            long expectedGeneration,
            ActionLabelNode label) {
        Location anchor = anchors.location(key.tableId())
                .orElseThrow(
                        () -> new IllegalStateException("No anchor for table " + key.tableId()));
        Location location = PrivateSceneGeometry.localToWorld(anchor, label.transform());
        String content = PrivatePresentationAssets.labelJson(label, player.locale(), messages);
        ActiveLabel active = state.activeLabel(key);
        if (active != null
                && active.content().equals(content)) {
            displays.teleport(player, location, active.display().entityId());
            state.putActiveLabel(
                    key,
                    new ActiveLabel(
                            expectedGeneration,
                            active.display(),
                            label,
                            content));
            removeIfStale(player, key, expectedGeneration);
            return;
        }
        remove(player, key);
        ClientTextDisplay display = displays.createText(location);
        display.name(content);
        display.rgba(0, 0, 0, 60);
        display.spawn(player);
        ActiveLabel replaced =
                state.putActiveLabel(
                        key,
                        new ActiveLabel(expectedGeneration, display, label, content));
        if (replaced != null && replaced.display() != display) {
            replaced.display().destroy(player);
        }
        removeIfStale(player, key, expectedGeneration);
    }

    private void removeIfStale(Player player, NodeKey key, long expectedGeneration) {
        DesiredNode current = state.desiredNode(key.viewer(), key);
        if (current == null || current.generation() != expectedGeneration) {
            remove(player, key);
        }
    }

}
