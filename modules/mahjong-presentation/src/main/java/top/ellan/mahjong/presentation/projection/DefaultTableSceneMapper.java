package top.ellan.mahjong.presentation.projection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import top.ellan.mahjong.presentation.projection.privateview.PrivateSceneProjector;
import top.ellan.mahjong.presentation.projection.publicview.PublicSceneProjector;
import top.ellan.mahjong.presentation.scene.SceneGraph;
import top.ellan.mahjong.presentation.scene.SceneInteractionBinding;
import top.ellan.mahjong.spi.TileInstanceId;

/** Coordinates independent public, private and interaction scene projectors. */
public final class DefaultTableSceneMapper implements TableSceneMapper {
    private final TableLayout layout;
    private final PublicSceneProjector publicScene;
    private final PrivateSceneProjector privateScene;
    private final InteractionSceneProjector interactionScene;

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
        this.layout = Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(assets, "assets");
        RuleTileFurnitureResolver furniture = new RuleTileFurnitureResolver(assets);
        publicScene = new PublicSceneProjector(assets, furniture);
        privateScene = new PrivateSceneProjector(overheadHeight, overheadEnabled);
        interactionScene = new InteractionSceneProjector(assets, overheadEnabled);
    }

    @Override
    public SceneGraph map(TableProjection projection) {
        Objects.requireNonNull(projection, "projection");
        Map<SceneNodeId, SceneNode> nodes = new LinkedHashMap<>();
        List<SceneInteractionBinding> bindings = new ArrayList<>();
        ResolvedTableLayout resolvedLayout =
                layout.resolve(projection.publicView().tablePresentation());
        Set<TileInstanceId> publiclyRevealedHands =
                publicScene.project(nodes, projection, resolvedLayout);
        privateScene.project(nodes, projection, resolvedLayout, publiclyRevealedHands);
        interactionScene.project(nodes, bindings, projection, resolvedLayout);
        return new SceneGraph(projection.tableId(), projection.revision(), nodes, bindings);
    }
}
