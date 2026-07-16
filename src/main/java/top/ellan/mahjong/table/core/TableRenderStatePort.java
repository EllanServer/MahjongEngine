package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.snapshot.TableRenderPrecomputeResult;
import top.ellan.mahjong.render.snapshot.TableRenderSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerHudSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Render-state port for a mahjong table session, exposing render triggers,
 * display lifecycle controls, and snapshot capture. Extends
 * {@link TableIdentityPort} so that identity queries are also available.
 */
public interface TableRenderStatePort extends TableIdentityPort {
    void render();

    void clearDisplays();

    boolean hasRegionDisplays();

    boolean hasStaleDisplayRegions();

    boolean isCenterChunkLoaded();

    void invalidateRenderFingerprints();

    void prepareRenderRequest();

    void completeRenderFlush();

    TableRenderSnapshot captureRenderSnapshot(long version, long cancellationNonce);

    TableRenderPrecomputeResult precomputeRender(TableRenderSnapshot snapshot);

    boolean applyRenderPrecompute(TableRenderPrecomputeResult result);

    void flushViewerActionsNow(UUID viewerId);

    void clearViewerActionMenuState(UUID viewerId);

    void setViewerActionMenuState(UUID viewerId, String menuState);

    String viewerActionMenuState(UUID viewerId);

    TableViewerOverlaySnapshot captureViewerOverlaySnapshot(Player viewer);

    TableViewerHudSnapshot captureViewerHudSnapshot(Locale locale, UUID viewerId);

    void updateViewerOverlayRegion(TableViewerOverlaySnapshot snapshot);

    void updateViewerActionRegions(TableViewerOverlaySnapshot snapshot);

    List<String> viewerOverlayRegionKeys();

    void removeManagedRegionDisplays(String regionKey);

    Component stateSummary(Player player);

    Component viewerOverlay(Player viewer);

    Component spectatorSeatOverlay(Player viewer, SeatWind wind);

    void inspectRender(Player viewer);
}
