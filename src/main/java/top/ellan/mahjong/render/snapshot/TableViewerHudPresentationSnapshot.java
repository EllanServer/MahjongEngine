package top.ellan.mahjong.render.snapshot;

import java.util.Objects;

/**
 * The two pieces of per-viewer HUD state captured during the same presentation poll.
 */
public record TableViewerHudPresentationSnapshot(
    TableViewerHudSnapshot hud,
    TableViewerActionBarSnapshot actionBar
) {
    public TableViewerHudPresentationSnapshot {
        Objects.requireNonNull(hud, "hud");
        Objects.requireNonNull(actionBar, "actionBar");
    }
}
