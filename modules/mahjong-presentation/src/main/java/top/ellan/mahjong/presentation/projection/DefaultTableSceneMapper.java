package top.ellan.mahjong.presentation.projection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.presentation.asset.TableSceneAssets;
import top.ellan.mahjong.presentation.layout.ResolvedTableLayout;
import top.ellan.mahjong.presentation.layout.TableLayout;
import top.ellan.mahjong.presentation.node.SceneNode;
import top.ellan.mahjong.presentation.node.SceneNodeId;
import top.ellan.mahjong.presentation.projection.asset.RuleTileFurnitureResolver;
import top.ellan.mahjong.presentation.projection.interaction.InteractionSceneProjector;
import top.ellan.mahjong.presentation.projection.interaction.ActionButtonMetrics;
import top.ellan.mahjong.presentation.projection.privateview.PrivateSceneProjector;
import top.ellan.mahjong.presentation.projection.publicview.PublicSceneProjector;
import top.ellan.mahjong.presentation.projection.support.ViewerZoneCounts;
import top.ellan.mahjong.presentation.scene.SceneGraph;
import top.ellan.mahjong.presentation.scene.SceneInteractionBinding;
import top.ellan.mahjong.spi.TileInstanceId;

/** Coordinates independent public, private and interaction scene projectors. */
public final class DefaultTableSceneMapper implements TableSceneMapper {
    private final TableLayout layout;
    private final PublicSceneProjector publicScene;
    private final PrivateSceneProjector privateScene;
    private final InteractionSceneProjector interactionScene;
    private final boolean overheadEnabled;

    public DefaultTableSceneMapper(TableLayout layout, TableSceneAssets assets) {
        this(layout, assets, 4.5D, true);
    }

    public DefaultTableSceneMapper(
            TableLayout layout, TableSceneAssets assets, double overheadHeight) {
        this(layout, assets, overheadHeight, true);
    }

    public DefaultTableSceneMapper(
            TableLayout layout,
            TableSceneAssets assets,
            double overheadHeight,
            boolean overheadEnabled) {
        this(layout, assets, overheadHeight, overheadEnabled, ActionButtonMetrics.fallback());
    }

    public DefaultTableSceneMapper(
            TableLayout layout,
            TableSceneAssets assets,
            double overheadHeight,
            boolean overheadEnabled,
            ActionButtonMetrics buttonMetrics) {
        this.layout = Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(assets, "assets");
        RuleTileFurnitureResolver furniture = new RuleTileFurnitureResolver(assets);
        publicScene = new PublicSceneProjector(assets, furniture);
        privateScene = new PrivateSceneProjector(overheadHeight, overheadEnabled);
        interactionScene =
                new InteractionSceneProjector(assets, overheadEnabled, buttonMetrics);
        this.overheadEnabled = overheadEnabled;
    }

    @Override
    public SceneGraph map(TableProjection projection) {
        Objects.requireNonNull(projection, "projection");
        HashMap<SceneNodeId, SceneNode> nodes =
                HashMap.newHashMap(expectedNodeCount(projection));
        ArrayList<SceneInteractionBinding> bindings =
                new ArrayList<>(expectedBindingCount(projection));
        ResolvedTableLayout resolvedLayout =
                layout.resolve(projection.publicView().tablePresentation());
        Set<TileInstanceId> publiclyRevealedHands =
                publicScene.project(nodes, projection, resolvedLayout);
        // Private tiles and hand actions are positioned from the same per-viewer counts, so they are
        // computed once for the frame and shared instead of recounted by each projector.
        ViewerZoneCounts viewerCounts = new ViewerZoneCounts();
        privateScene.project(
                nodes, projection, resolvedLayout, publiclyRevealedHands, viewerCounts);
        interactionScene.project(nodes, bindings, projection, resolvedLayout, viewerCounts);
        return SceneGraph.takeOwnership(
                projection.tableId(), projection.revision(), nodes, bindings);
    }

    private int expectedNodeCount(TableProjection projection) {
        int nodes = 1 + projection.publicView().tiles().size();
        if (!projection.privateViews().isEmpty()) {
            nodes += 1 + projection.publicView().attributes().size();
        }
        boolean activeOverhead = overheadEnabled && projection.lifecycle().acceptsRuleActions();
        for (var privateView : projection.privateViews().values()) {
            nodes += privateView.tiles().size() + privateView.attributes().size();
            if (activeOverhead) {
                nodes++;
            }
        }
        for (var actions : projection.authorizedActions().values()) {
            for (var action : actions) {
                nodes += action.legalAction().actionPresentation().placement()
                                == top.ellan.mahjong.spi.ActionPlacement.HAND_TILE
                        ? 1
                        : 2;
            }
        }
        if (activeOverhead) {
            nodes += projection.privateViews().size() * 2;
        }
        return nodes;
    }

    private int expectedBindingCount(TableProjection projection) {
        int bindings = 0;
        for (var actions : projection.authorizedActions().values()) {
            bindings += actions.size();
        }
        if (overheadEnabled && projection.lifecycle().acceptsRuleActions()) {
            bindings += projection.privateViews().size();
        }
        return bindings;
    }
}
