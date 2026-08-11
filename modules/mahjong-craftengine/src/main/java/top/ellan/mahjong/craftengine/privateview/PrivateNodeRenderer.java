package top.ellan.mahjong.craftengine.privateview;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.ellan.mahjong.craftengine.port.PlayerTextResolver;
import top.ellan.mahjong.platform.paper.anchor.TableAnchorLookup;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.ActiveItem;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.ActiveLabel;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.DesiredNode;
import top.ellan.mahjong.craftengine.privateview.PrivateProjectionState.NodeKey;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.presentation.node.ActionLabelNode;
import top.ellan.mahjong.presentation.node.CameraNode;
import top.ellan.mahjong.presentation.node.HudNode;
import top.ellan.mahjong.presentation.node.PrivateItemNode;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.TileInstanceId;

/** Region-thread renderer for private items, labels and HUD state. */
final class PrivateNodeRenderer implements AutoCloseable {
    private final TableAnchorLookup anchors;
    private final PrivateProjectionState state;
    private final PlayerRegionTaskScheduler tasks;
    private final SparrowDisplayGateway displays;
    private final Predicate<PlayerId> cameraViewing;
    private final PlayerTextResolver messages;
    private final HandTileSelectionController selections;

    PrivateNodeRenderer(
            TableAnchorLookup anchors,
            PrivateProjectionState state,
            PlayerRegionTaskScheduler tasks,
            SparrowDisplayGateway displays,
            double selectionRaise,
            Predicate<PlayerId> cameraViewing,
            PlayerTextResolver messages) {
        this.anchors = anchors;
        this.state = state;
        this.tasks = tasks;
        this.displays = displays;
        this.cameraViewing = cameraViewing;
        this.messages = messages;
        selections = new HandTileSelectionController(tasks, this::moveHandTile, selectionRaise);
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
        applyItem(player, key, expectedGeneration, (PrivateItemNode) target.node());
    }

    void remove(Player player, NodeKey key) {
        ActiveItem active = state.removeActiveItem(key);
        if (active != null) {
            displays.destroyItem(player, active.display());
        }
        ActiveLabel label = state.removeActiveLabel(key);
        if (label != null) {
            displays.destroyText(player, label.display());
        }
    }

    void forget(NodeKey key) {
        state.removeActiveItem(key);
        state.removeActiveLabel(key);
    }

    void refreshHud(Player player, PlayerId viewer) {
        String text = state.desiredNodes(viewer).values().stream()
                .map(DesiredNode::node)
                .filter(HudNode.class::isInstance)
                .map(HudNode.class::cast)
                .sorted(Comparator.comparing(HudNode::contentKey))
                .map(node -> node.contentKey() + '=' + node.contentValue())
                .collect(Collectors.joining("  "));
        player.sendActionBar(Component.text(text));
    }

    void showSelection(
            TableId tableId, PlayerId playerId, Optional<TileInstanceId> selectedTile) {
        selections.showSelection(tableId, playerId, selectedTile);
    }

    void hideActionLabels(Player player, PlayerId viewer) {
        for (ActiveLabel label : state.removeActiveLabels(viewer)) {
            displays.destroyText(player, label.display());
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
        selections.onQuit(viewer);
    }

    @Override
    public void close() {
        for (PrivateProjectionState.ViewerItems viewer : state.activeItemSnapshot()) {
            Player player = Bukkit.getPlayer(viewer.viewer().value());
            if (player != null) {
                for (ActiveItem item : viewer.items()) {
                    tasks.execute(player, () -> displays.destroyItem(player, item.display()));
                }
            }
        }
        for (PrivateProjectionState.ViewerLabels viewer : state.activeLabelSnapshot()) {
            Player player = Bukkit.getPlayer(viewer.viewer().value());
            if (player != null) {
                for (ActiveLabel label : viewer.labels()) {
                    tasks.execute(player, () -> displays.destroyText(player, label.display()));
                }
            }
        }
        selections.clear();
    }

    private void applyItem(
            Player player,
            NodeKey key,
            long expectedGeneration,
            PrivateItemNode item) {
        Location anchor = anchors.location(key.tableId())
                .orElseThrow(
                        () -> new IllegalStateException("No anchor for table " + key.tableId()));
        Location location = PrivateSceneGeometry.localToWorld(
                anchor,
                selections.presentedTransform(key.tableId(), key.viewer(), item));
        String asset = PrivatePresentationAssets.itemAsset(item);
        ActiveItem active = state.activeItem(key);
        if (active != null
                && PrivatePresentationAssets.itemAsset(active.node()).equals(asset)) {
            displays.teleport(player, location, active.display().entityID());
            state.putActiveItem(
                    key, new ActiveItem(expectedGeneration, active.display(), item));
            removeIfStale(player, key, expectedGeneration);
            return;
        }
        remove(player, key);
        BukkitItemDefinition definition = CraftEngineItems.byId(asset);
        if (definition == null) {
            throw new IllegalStateException("Missing CraftEngine item " + asset);
        }
        ItemStack stack = definition.buildBukkitItem(player);
        FakeItemDisplay display = displays.createItem(location);
        display.item(stack);
        display.spawn(player);
        ActiveItem replaced =
                state.putActiveItem(key, new ActiveItem(expectedGeneration, display, item));
        if (replaced != null && replaced.display() != display) {
            displays.destroyItem(player, replaced.display());
        }
        removeIfStale(player, key, expectedGeneration);
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
            displays.teleport(player, location, active.display().entityID());
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
        FakeTextDisplay display = displays.createText(
                location);
        display.name(content);
        display.rgba(0, 0, 0, 60);
        display.spawn(player);
        ActiveLabel replaced =
                state.putActiveLabel(
                        key,
                        new ActiveLabel(expectedGeneration, display, label, content));
        if (replaced != null && replaced.display() != display) {
            displays.destroyText(player, replaced.display());
        }
        removeIfStale(player, key, expectedGeneration);
    }

    private void removeIfStale(Player player, NodeKey key, long expectedGeneration) {
        DesiredNode current = state.desiredNode(key.viewer(), key);
        if (current == null || current.generation() != expectedGeneration) {
            remove(player, key);
        }
    }

    private void moveHandTile(
            Player player,
            TableId tableId,
            PlayerId viewer,
            TileInstanceId tileInstanceId) {
        if (tileInstanceId == null || !player.isOnline()) {
            return;
        }
        NodeKey key = state.handTile(tableId, viewer, tileInstanceId);
        if (key == null) {
            return;
        }
        DesiredNode desired = state.desiredNode(viewer, key);
        if (desired == null || !(desired.node() instanceof PrivateItemNode item)) {
            return;
        }
        ActiveItem active = state.activeItem(key);
        if (active == null || active.generation() != desired.generation()) {
            apply(player, key, desired.generation());
            return;
        }
        Location anchor = anchors.location(tableId).orElse(null);
        if (anchor == null) {
            return;
        }
        displays.teleport(
                player,
                PrivateSceneGeometry.localToWorld(
                        anchor,
                        selections.presentedTransform(key.tableId(), key.viewer(), item)),
                active.display().entityID());
    }
}
