package top.ellan.mahjong.render.snapshot;

import net.kyori.adventure.text.Component;

public record TableViewerActionBarSnapshot(
    Component message,
    boolean visible
) {
    public static TableViewerActionBarSnapshot hidden() {
        return new TableViewerActionBarSnapshot(Component.empty(), false);
    }
}
