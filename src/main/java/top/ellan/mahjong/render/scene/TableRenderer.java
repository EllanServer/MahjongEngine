package top.ellan.mahjong.render.scene;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.layout.TableRenderLayout;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableSeatRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerPromptSnapshot;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Facade that delegates table scene rendering to the region-specific renderer
 * classes. The public API is preserved exactly; each method forwards to the
 * matching implementation in {@link TableStructureRenderer}, {@link SeatRenderer},
 * {@link WallRenderer}, {@link HandRenderer}, {@link DiscardRenderer},
 * {@link MeldRenderer}, {@link StickRenderer}, {@link CenterLabelRenderer} or
 * {@link ViewerOverlayRenderer}. Shared constants live in
 * {@link TableRenderConstants} and shared geometry in {@link TableGeometry}.
 */
public final class TableRenderer {
    public List<Entity> renderTableStructure(TableRenderSubject session) {
        return TableStructureRenderer.renderTableStructure(session);
    }

    public List<Entity> renderTableStructure(TableRenderSubject session, TableRenderLayout.LayoutPlan plan) {
        return TableStructureRenderer.renderTableStructure(session, plan);
    }

    public TableGeometry.TableDiagnostics inspectTable(TableRenderSubject session) {
        return TableStructureRenderer.inspectTable(session);
    }

    public List<TableGeometry.StickDiagnostics> inspectSticks(TableRenderSubject session) {
        return StickRenderer.inspectSticks(session);
    }

    public List<Entity> renderSeatLabels(TableRenderSubject session, SeatWind wind) {
        return SeatRenderer.renderSeatLabels(session, wind);
    }

    public List<Entity> renderSeatVisual(TableRenderSubject session, SeatWind wind) {
        return SeatRenderer.renderSeatVisual(session, wind);
    }

    public List<Entity> renderSeatLabels(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return SeatRenderer.renderSeatLabels(session, seat, plan);
    }

    public List<DisplayEntities.EntitySpec> renderSeatLabelSpecs(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return SeatRenderer.renderSeatLabelSpecs(session, seat, plan);
    }

    public SeatRenderer.SeatLabelRenderPlan renderSeatLabelPlan(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return SeatRenderer.renderSeatLabelPlan(session, seat, plan);
    }

    public List<Entity> renderSticks(TableRenderSubject session, SeatWind wind) {
        return StickRenderer.renderSticks(session, wind);
    }

    public List<Entity> renderWall(TableRenderSubject session) {
        return WallRenderer.renderWall(session);
    }

    public List<Entity> renderWall(TableRenderSubject session, TableRenderLayout.LayoutPlan plan) {
        return WallRenderer.renderWall(session, plan);
    }

    public List<Entity> renderWallTile(TableRenderSubject session, TableRenderLayout.LayoutPlan plan, int wallIndex) {
        return WallRenderer.renderWallTile(session, plan, wallIndex);
    }

    public List<DisplayEntities.EntitySpec> renderWallTileSpecs(TableRenderSubject session, TableRenderLayout.LayoutPlan plan, int wallIndex) {
        return WallRenderer.renderWallTileSpecs(session, plan, wallIndex);
    }

    public List<Entity> renderSticks(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return StickRenderer.renderSticks(session, seat, plan);
    }

    public List<Entity> renderDora(TableRenderSubject session) {
        return WallRenderer.renderDora(session);
    }

    public List<Entity> renderDora(TableRenderSubject session, TableRenderLayout.LayoutPlan plan) {
        return WallRenderer.renderDora(session, plan);
    }

    public List<DisplayEntities.EntitySpec> renderDoraSpecs(TableRenderSubject session, TableRenderLayout.LayoutPlan plan) {
        return WallRenderer.renderDoraSpecs(session, plan);
    }

    public List<Entity> renderCenterLabel(TableRenderSubject session) {
        return CenterLabelRenderer.renderCenterLabel(session);
    }

    public List<Entity> renderCenterLabel(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableRenderLayout.LayoutPlan plan
    ) {
        return CenterLabelRenderer.renderCenterLabel(session, snapshot, plan);
    }

    public List<DisplayEntities.EntitySpec> renderCenterLabelSpecs(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableRenderLayout.LayoutPlan plan
    ) {
        return CenterLabelRenderer.renderCenterLabelSpecs(session, snapshot, plan);
    }

    public List<Entity> renderViewerOverlay(TableRenderSubject session, Player viewer) {
        return ViewerOverlayRenderer.renderViewerOverlay(session, viewer);
    }

    public List<Entity> renderViewerOverlay(TableRenderSubject session, TableViewerOverlaySnapshot snapshot) {
        return ViewerOverlayRenderer.renderViewerOverlay(session, snapshot);
    }

    public List<Entity> renderDiscards(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return DiscardRenderer.renderDiscards(session, seat, plan);
    }

    public List<Entity> renderDiscardTile(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int discardIndex
    ) {
        return DiscardRenderer.renderDiscardTile(session, seat, plan, discardIndex);
    }

    public List<DisplayEntities.EntitySpec> renderDiscardTileSpecs(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int discardIndex
    ) {
        return DiscardRenderer.renderDiscardTileSpecs(session, seat, plan, discardIndex);
    }

    public List<Entity> renderHandPublic(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return HandRenderer.renderHandPublic(session, snapshot, seat, plan);
    }

    public List<Entity> renderHandPublicTile(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        return HandRenderer.renderHandPublicTile(session, snapshot, seat, plan, tileIndex);
    }

    public List<DisplayEntities.EntitySpec> renderHandPublicTileSpecs(
        TableRenderSubject session,
        TableRenderSnapshot snapshot,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        return HandRenderer.renderHandPublicTileSpecs(session, snapshot, seat, plan, tileIndex);
    }

    public List<Entity> renderMelds(TableRenderSubject session, SeatWind wind) {
        return MeldRenderer.renderMelds(session, wind);
    }

    public List<Entity> renderMelds(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return MeldRenderer.renderMelds(session, seat, plan);
    }

    public HandRenderer.HandTileRenderPlan renderHandPrivateTilePlan(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int tileIndex
    ) {
        return HandRenderer.renderHandPrivateTilePlan(session, seat, plan, tileIndex);
    }

    public List<DisplayEntities.EntitySpec> renderMeldSpecs(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan
    ) {
        return MeldRenderer.renderMeldSpecs(session, seat, plan);
    }

    public List<DisplayEntities.EntitySpec> renderMeldTileSpecs(
        TableRenderSubject session,
        TableSeatRenderSnapshot seat,
        TableRenderLayout.SeatLayoutPlan plan,
        int meldIndex
    ) {
        return MeldRenderer.renderMeldTileSpecs(session, seat, plan, meldIndex);
    }

    public Location seatAnchorLocation(TableRenderSubject session, SeatWind wind) {
        return SeatRenderer.seatAnchorLocation(session, wind);
    }

    public float seatFacingYaw(SeatWind wind) {
        return SeatRenderer.seatFacingYaw(wind);
    }

    public List<DisplayEntities.EntitySpec> renderViewerOverlaySpecs(TableRenderSubject session, TableViewerOverlaySnapshot snapshot) {
        return ViewerOverlayRenderer.renderViewerOverlaySpecs(session, snapshot);
    }

    public List<DisplayEntities.EntitySpec> renderViewerPromptSpecs(TableRenderSubject session, TableViewerPromptSnapshot snapshot) {
        return ViewerOverlayRenderer.renderViewerPromptSpecs(session, snapshot);
    }

    public ViewerOverlayRenderer.ViewerActionOverlayPlan renderViewerActionOverlayPlan(
        TableRenderSubject session,
        TableViewerActionOverlaySnapshot snapshot
    ) {
        return ViewerOverlayRenderer.renderViewerActionOverlayPlan(session, snapshot);
    }

    static Location tableFurnitureAnchor(Location tableCenter, String furnitureId) {
        return TableStructureRenderer.tableFurnitureAnchor(tableCenter, furnitureId);
    }

    static Location seatFurnitureAnchor(Location location, SeatWind wind, String furnitureId) {
        return SeatRenderer.seatFurnitureAnchor(location, wind, furnitureId);
    }
}
