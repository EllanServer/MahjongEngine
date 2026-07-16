package top.ellan.mahjong.render.snapshot;

import net.kyori.adventure.text.format.NamedTextColor;

public record TableViewerActionButtonSnapshot(
    String actionId,
    String label,
    NamedTextColor color,
    String command,
    float hitboxWidth,
    Placement placement
) {
    public TableViewerActionButtonSnapshot(
        String actionId,
        String label,
        NamedTextColor color,
        String command,
        float hitboxWidth
    ) {
        this(actionId, label, color, command, hitboxWidth, Placement.ACTION_ROW);
    }

    public TableViewerActionButtonSnapshot {
        placement = placement == null ? Placement.ACTION_ROW : placement;
    }

    public enum Placement {
        ACTION_ROW,
        RIGHT_SIDE,
        OVERHEAD_CENTER
    }
}
