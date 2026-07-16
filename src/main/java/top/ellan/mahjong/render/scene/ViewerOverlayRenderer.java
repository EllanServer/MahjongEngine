package top.ellan.mahjong.render.scene;

import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.render.TableRenderSubject;
import top.ellan.mahjong.render.snapshot.TableViewerActionOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerActionButtonSnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerOverlaySnapshot;
import top.ellan.mahjong.render.snapshot.TableViewerPromptSnapshot;
import top.ellan.mahjong.render.snapshot.TableSpectatorSeatOverlaySnapshot;
import top.ellan.mahjong.presentation.TableFeedbackPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Renders viewer-specific overlays: the main overlay label, spectator seat
 * overlays, viewer prompts and action buttons.
 */
public final class ViewerOverlayRenderer {
    private ViewerOverlayRenderer() {
    }

    public static List<Entity> renderViewerOverlay(TableRenderSubject session, Player viewer) {
        Location center = TableGeometry.displayCenter(session);
        UUID viewerId = viewer.getUniqueId();
        List<Entity> spawned = new ArrayList<>(session.isSpectator(viewerId) ? 5 : 1);
        // Seated players already have the compact boss bar plus a decision prompt beside
        // their hand. Keep the detailed table overlay spectator-only so it cannot stack
        // over the centre announcement and the local prompt.
        if (session.isSpectator(viewerId)) {
            spawned.add(DisplayEntities.spawnLabel(
                session.bukkitPlugin(),
                center.clone().add(0.0D, TableRenderConstants.VIEWER_OVERLAY_LABEL_Y_OFFSET + TableRenderConstants.FLOATING_TEXT_Y_OFFSET, 0.0D),
                session.viewerOverlay(viewer),
                TableRenderConstants.VIEWER_OVERLAY_BACKGROUND,
                List.of(viewerId)
            ));
        }
        if (session.isSpectator(viewerId)) {
            for (SeatWind wind : SeatWind.values()) {
                spawned.add(DisplayEntities.spawnLabel(
                    session.bukkitPlugin(),
                    TableGeometry.add(TableGeometry.handDirectionBase(center, wind), TableGeometry.offsetAcrossSeat(wind, TableRenderConstants.SPECTATOR_OVERLAY_ACROSS_OFFSET)).add(0.0D, TableRenderConstants.SPECTATOR_OVERLAY_Y_OFFSET + TableRenderConstants.FLOATING_TEXT_Y_OFFSET, 0.0D),
                    session.spectatorSeatOverlay(viewer, wind),
                    TableRenderConstants.SPECTATOR_SEAT_OVERLAY_BACKGROUND,
                    List.of(viewerId)
                ));
            }
        }
        return spawned;
    }

    public static List<Entity> renderViewerOverlay(TableRenderSubject session, TableViewerOverlaySnapshot snapshot) {
        Location center = TableGeometry.displayCenter(session);
        List<Entity> spawned = new ArrayList<>(snapshot.spectatorSeatOverlays().isEmpty() ? 1 : 1 + snapshot.spectatorSeatOverlays().size());
        if (snapshot.spectator()) {
            spawned.add(DisplayEntities.spawnLabel(
                session.bukkitPlugin(),
                center.clone().add(0.0D, TableRenderConstants.VIEWER_OVERLAY_LABEL_Y_OFFSET + TableRenderConstants.FLOATING_TEXT_Y_OFFSET, 0.0D),
                snapshot.overlay(),
                TableRenderConstants.VIEWER_OVERLAY_BACKGROUND,
                List.of(snapshot.viewerId())
            ));
        }
        if (snapshot.spectator()) {
            for (TableSpectatorSeatOverlaySnapshot seatOverlay : snapshot.spectatorSeatOverlays()) {
                spawned.add(DisplayEntities.spawnLabel(
                    session.bukkitPlugin(),
                    TableGeometry.add(TableGeometry.handDirectionBase(center, seatOverlay.wind()), TableGeometry.offsetAcrossSeat(seatOverlay.wind(), TableRenderConstants.SPECTATOR_OVERLAY_ACROSS_OFFSET)).add(0.0D, TableRenderConstants.SPECTATOR_OVERLAY_Y_OFFSET + TableRenderConstants.FLOATING_TEXT_Y_OFFSET, 0.0D),
                    seatOverlay.overlay(),
                    TableRenderConstants.SPECTATOR_SEAT_OVERLAY_BACKGROUND,
                    List.of(snapshot.viewerId())
                ));
            }
        }
        return spawned;
    }

    public static List<DisplayEntities.EntitySpec> renderViewerOverlaySpecs(TableRenderSubject session, TableViewerOverlaySnapshot snapshot) {
        Location center = TableGeometry.displayCenter(session);
        List<DisplayEntities.EntitySpec> specs = new ArrayList<>(
            snapshot.spectatorSeatOverlays().isEmpty() ? 1 : 1 + snapshot.spectatorSeatOverlays().size()
        );
        if (snapshot.spectator()) {
            specs.add(DisplayEntities.labelSpec(
                center.clone().add(0.0D, TableRenderConstants.VIEWER_OVERLAY_LABEL_Y_OFFSET + TableRenderConstants.FLOATING_TEXT_Y_OFFSET, 0.0D),
                snapshot.overlay(),
                TableRenderConstants.VIEWER_OVERLAY_BACKGROUND,
                List.of(snapshot.viewerId()),
                Display.Billboard.CENTER,
                0.0F,
                0.0F,
                true
            ));
        }
        if (snapshot.spectator()) {
            for (TableSpectatorSeatOverlaySnapshot seatOverlay : snapshot.spectatorSeatOverlays()) {
                specs.add(DisplayEntities.labelSpec(
                    TableGeometry.add(TableGeometry.handDirectionBase(center, seatOverlay.wind()), TableGeometry.offsetAcrossSeat(seatOverlay.wind(), TableRenderConstants.SPECTATOR_OVERLAY_ACROSS_OFFSET)).add(0.0D, TableRenderConstants.SPECTATOR_OVERLAY_Y_OFFSET + TableRenderConstants.FLOATING_TEXT_Y_OFFSET, 0.0D),
                    seatOverlay.overlay(),
                    TableRenderConstants.SPECTATOR_SEAT_OVERLAY_BACKGROUND,
                    List.of(snapshot.viewerId()),
                    Display.Billboard.CENTER,
                    0.0F,
                    0.0F,
                    true
                ));
            }
        }
        return List.copyOf(specs);
    }

    public static List<DisplayEntities.EntitySpec> renderViewerPromptSpecs(TableRenderSubject session, TableViewerPromptSnapshot snapshot) {
        if (snapshot == null || !snapshot.visible()) {
            return List.of();
        }
        Location center = TableGeometry.displayCenter(session);
        SeatWind viewerSeat = session.seatOf(snapshot.viewerId());
        Location promptLocation = viewerSeat == null
            ? center.clone().add(0.0D, TableRenderConstants.VIEWER_PROMPT_Y_OFFSET, 0.0D)
            : TableGeometry.add(
                TableGeometry.handDirectionBase(center, viewerSeat),
                TableGeometry.offsetTowardTableCenter(viewerSeat, 0.42D)
            ).add(0.0D, TableRenderConstants.VIEWER_PROMPT_Y_OFFSET, 0.0D);
        return List.of(DisplayEntities.labelSpec(
            promptLocation,
            snapshot.prompt(),
            TableRenderConstants.VIEWER_PROMPT_BACKGROUND,
            List.of(snapshot.viewerId()),
            viewerSeat == null ? Display.Billboard.CENTER : Display.Billboard.FIXED,
            viewerSeat == null ? 0.0F : TableGeometry.seatYaw(viewerSeat),
            0.0F,
            true
        ));
    }

    public static List<DisplayEntities.EntitySpec> renderViewerActionOverlaySpecs(TableRenderSubject session, TableViewerActionOverlaySnapshot snapshot) {
        return renderViewerActionOverlayPlan(session, snapshot).entitySpecs();
    }

    public static ViewerActionOverlayPlan renderViewerActionOverlayPlan(
        TableRenderSubject session,
        TableViewerActionOverlaySnapshot snapshot
    ) {
        if (snapshot == null || snapshot.actionButtons().isEmpty()) {
            return ViewerActionOverlayPlan.empty();
        }
        Location center = TableGeometry.displayCenter(session);
        List<DisplayEntities.EntitySpec> specs = new ArrayList<>(snapshot.actionButtons().size() * 2);
        List<DisplayInteractionRayRegistry.RayInteraction> flatInteractions = new ArrayList<>(
            snapshot.actionButtons().size()
        );
        appendViewerActionButtonSpecs(
            session,
            snapshot.viewerId(),
            snapshot.actionButtons(),
            center,
            specs,
            flatInteractions
        );
        return new ViewerActionOverlayPlan(specs, flatInteractions);
    }

    private static void appendViewerActionButtonSpecs(
        TableRenderSubject session,
        UUID viewerId,
        List<TableViewerActionButtonSnapshot> actionButtons,
        Location center,
        List<DisplayEntities.EntitySpec> specs,
        List<DisplayInteractionRayRegistry.RayInteraction> flatInteractions
    ) {
        if (actionButtons.isEmpty()) {
            return;
        }
        SeatWind viewerSeat = session.seatOf(viewerId);
        Location actionAnchor = viewerSeat == null
            ? center.clone().add(0.0D, TableRenderConstants.OVERLAY_ACTION_Y_OFFSET, 0.0D)
            : TableGeometry.add(TableGeometry.handDirectionBase(center, viewerSeat), TableGeometry.offsetTowardTableCenter(viewerSeat, 0.42D)).add(0.0D, TableRenderConstants.OVERLAY_ACTION_Y_OFFSET, 0.0D);
        float yaw = viewerSeat == null ? 0.0F : TableGeometry.seatYaw(viewerSeat);
        List<TableViewerActionButtonSnapshot> rowButtons = new ArrayList<>();
        List<TableViewerActionButtonSnapshot> rightButtons = new ArrayList<>();
        List<TableViewerActionButtonSnapshot> overheadButtons = new ArrayList<>();
        for (TableViewerActionButtonSnapshot button : actionButtons) {
            switch (button.placement()) {
                case ACTION_ROW -> rowButtons.add(button);
                case RIGHT_SIDE -> rightButtons.add(button);
                case OVERHEAD_CENTER -> overheadButtons.add(button);
            }
        }

        appendActionRowButtonSpecs(
            session,
            viewerId,
            rowButtons,
            viewerSeat,
            actionAnchor,
            yaw,
            specs,
            flatInteractions
        );
        appendRightSideButtonSpecs(
            session,
            viewerId,
            rowButtons,
            rightButtons,
            viewerSeat,
            actionAnchor,
            yaw,
            specs,
            flatInteractions
        );
        appendOverheadButtonSpecs(session, viewerId, overheadButtons, specs);
    }

    private static void appendActionRowButtonSpecs(
        TableRenderSubject session,
        UUID viewerId,
        List<TableViewerActionButtonSnapshot> actionButtons,
        SeatWind viewerSeat,
        Location actionAnchor,
        float yaw,
        List<DisplayEntities.EntitySpec> specs,
        List<DisplayInteractionRayRegistry.RayInteraction> flatInteractions
    ) {
        int row = 0;
        for (int rowStart = 0; rowStart < actionButtons.size(); rowStart += TableRenderConstants.OVERLAY_ACTION_BUTTONS_PER_ROW) {
            int rowEnd = Math.min(actionButtons.size(), rowStart + TableRenderConstants.OVERLAY_ACTION_BUTTONS_PER_ROW);
            List<TableViewerActionButtonSnapshot> rowButtons = actionButtons.subList(rowStart, rowEnd);
            double rowWidth = viewerActionRowWidth(rowButtons);
            double cursor = -rowWidth / 2.0D;
            for (TableViewerActionButtonSnapshot button : rowButtons) {
                double buttonWidth = viewerActionButtonWidth(button);
                double xOffset = cursor + buttonWidth / 2.0D;
                cursor += buttonWidth + TableRenderConstants.OVERLAY_ACTION_BUTTON_GAP;
                Location labelLocation = viewerSeat == null
                    ? actionAnchor.clone().add(xOffset, -row * TableRenderConstants.VIEWER_ACTION_BUTTON_ROW_STEP, 0.0D)
                    : TableGeometry.add(actionAnchor.clone().add(0.0D, -row * TableRenderConstants.VIEWER_ACTION_BUTTON_ROW_STEP, 0.0D), TableGeometry.offsetAcrossSeat(viewerSeat, xOffset));
                appendButtonSpecs(
                    session,
                    viewerId,
                    button,
                    labelLocation,
                    (float) buttonWidth,
                    TableRenderConstants.OVERLAY_ACTION_BUTTON_HEIGHT,
                    Display.Billboard.FIXED,
                    yaw,
                    viewerActionAcrossAxis(viewerSeat),
                    specs,
                    flatInteractions
                );
            }
            row++;
        }
    }

    private static void appendRightSideButtonSpecs(
        TableRenderSubject session,
        UUID viewerId,
        List<TableViewerActionButtonSnapshot> actionButtons,
        List<TableViewerActionButtonSnapshot> rightButtons,
        SeatWind viewerSeat,
        Location actionAnchor,
        float yaw,
        List<DisplayEntities.EntitySpec> specs,
        List<DisplayInteractionRayRegistry.RayInteraction> flatInteractions
    ) {
        if (rightButtons.isEmpty()) {
            return;
        }
        int firstRowEnd = Math.min(actionButtons.size(), TableRenderConstants.OVERLAY_ACTION_BUTTONS_PER_ROW);
        double rowWidth = viewerActionRowWidth(actionButtons.subList(0, firstRowEnd));
        double cursor = rowWidth == 0.0D
            ? TableRenderConstants.VIEWER_PINNED_ACTION_EMPTY_RIGHT_EDGE
            : -rowWidth / 2.0D - TableRenderConstants.VIEWER_PINNED_ACTION_GAP;
        for (TableViewerActionButtonSnapshot button : rightButtons) {
            float buttonWidth = viewerActionButtonWidth(button);
            double xOffset = cursor - buttonWidth / 2.0D;
            cursor -= buttonWidth + TableRenderConstants.VIEWER_PINNED_ACTION_GAP;
            Location labelLocation = viewerSeat == null
                ? actionAnchor.clone().add(xOffset, 0.0D, 0.0D)
                : TableGeometry.add(actionAnchor, TableGeometry.offsetAcrossSeat(viewerSeat, xOffset));
            appendButtonSpecs(
                session,
                viewerId,
                button,
                labelLocation,
                buttonWidth,
                TableRenderConstants.OVERLAY_ACTION_BUTTON_HEIGHT,
                Display.Billboard.FIXED,
                yaw,
                viewerActionAcrossAxis(viewerSeat),
                specs,
                flatInteractions
            );
        }
    }

    private static void appendOverheadButtonSpecs(
        TableRenderSubject session,
        UUID viewerId,
        List<TableViewerActionButtonSnapshot> overheadButtons,
        List<DisplayEntities.EntitySpec> specs
    ) {
        if (overheadButtons.isEmpty()) {
            return;
        }
        double yOffset = Math.max(
            TableRenderConstants.OVERHEAD_RETURN_BUTTON_MIN_Y_OFFSET,
            session.settings().tables().overheadView().height() / 2.0D
        );
        Location anchor = session.center().clone().add(0.0D, yOffset, 0.0D);
        SeatWind viewerSeat = session.seatOf(viewerId);
        List<TableViewerActionButtonSnapshot> decisionButtons = new ArrayList<>();
        List<TableViewerActionButtonSnapshot> returnButtons = new ArrayList<>();
        for (TableViewerActionButtonSnapshot button : overheadButtons) {
            if ("view-river".equals(button.actionId())) {
                returnButtons.add(button);
            } else {
                decisionButtons.add(button);
            }
        }

        List<List<TableViewerActionButtonSnapshot>> rows = new ArrayList<>();
        for (int rowStart = 0; rowStart < decisionButtons.size(); rowStart += TableRenderConstants.OVERHEAD_ACTION_BUTTONS_PER_ROW) {
            rows.add(decisionButtons.subList(
                rowStart,
                Math.min(decisionButtons.size(), rowStart + TableRenderConstants.OVERHEAD_ACTION_BUTTONS_PER_ROW)
            ));
        }
        for (TableViewerActionButtonSnapshot returnButton : returnButtons) {
            rows.add(List.of(returnButton));
        }

        double totalDepth = overheadRowsDepth(rows, decisionButtons.size());
        double depthCursor = -totalDepth / 2.0D;
        int decisionRowCount = (decisionButtons.size() + TableRenderConstants.OVERHEAD_ACTION_BUTTONS_PER_ROW - 1)
            / TableRenderConstants.OVERHEAD_ACTION_BUTTONS_PER_ROW;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex > 0) {
                depthCursor += rowIndex == decisionRowCount
                    ? TableRenderConstants.OVERHEAD_RETURN_GROUP_GAP
                    : TableRenderConstants.OVERHEAD_ACTION_ROW_GAP;
            }
            List<TableViewerActionButtonSnapshot> row = rows.get(rowIndex);
            double rowDepth = overheadRowDepth(row);
            double rowCenterOffset = depthCursor + rowDepth / 2.0D;
            depthCursor += rowDepth;

            double rowWidth = overheadRowWidth(row);
            double widthCursor = -rowWidth / 2.0D;
            for (TableViewerActionButtonSnapshot button : row) {
                float buttonWidth = overheadButtonWidth(button);
                double acrossOffset = widthCursor + buttonWidth / 2.0D;
                widthCursor += buttonWidth + TableRenderConstants.OVERLAY_ACTION_BUTTON_GAP;
                Location labelLocation = overheadScreenPlaneLocation(anchor, viewerSeat, acrossOffset, rowCenterOffset);
                appendButtonSpecs(
                    session,
                    viewerId,
                    button,
                    labelLocation,
                    buttonWidth,
                    TableRenderConstants.OVERHEAD_RETURN_BUTTON_HEIGHT,
                    Display.Billboard.CENTER,
                    0.0F,
                    null,
                    specs,
                    null
                );
            }
        }
    }

    private static double overheadRowsDepth(
        List<List<TableViewerActionButtonSnapshot>> rows,
        int decisionButtonCount
    ) {
        if (rows.isEmpty()) {
            return 0.0D;
        }
        int decisionRowCount = (decisionButtonCount + TableRenderConstants.OVERHEAD_ACTION_BUTTONS_PER_ROW - 1)
            / TableRenderConstants.OVERHEAD_ACTION_BUTTONS_PER_ROW;
        double depth = 0.0D;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex > 0) {
                depth += rowIndex == decisionRowCount
                    ? TableRenderConstants.OVERHEAD_RETURN_GROUP_GAP
                    : TableRenderConstants.OVERHEAD_ACTION_ROW_GAP;
            }
            depth += overheadRowDepth(rows.get(rowIndex));
        }
        return depth;
    }

    private static double overheadRowDepth(List<TableViewerActionButtonSnapshot> row) {
        double depth = 0.0D;
        for (TableViewerActionButtonSnapshot button : row) {
            depth = Math.max(depth, overheadButtonWidth(button));
        }
        return depth;
    }

    private static double overheadRowWidth(List<TableViewerActionButtonSnapshot> row) {
        double width = 0.0D;
        for (TableViewerActionButtonSnapshot button : row) {
            width += overheadButtonWidth(button);
        }
        return width + Math.max(0, row.size() - 1) * TableRenderConstants.OVERLAY_ACTION_BUTTON_GAP;
    }

    private static float overheadButtonWidth(TableViewerActionButtonSnapshot button) {
        float width = viewerActionButtonWidth(button);
        return "view-river".equals(button.actionId())
            ? Math.max(TableRenderConstants.OVERHEAD_RETURN_BUTTON_MIN_WIDTH, width)
            : width;
    }

    private static Location overheadScreenPlaneLocation(
        Location anchor,
        SeatWind viewerSeat,
        double acrossOffset,
        double depthOffset
    ) {
        if (viewerSeat == null) {
            return anchor.clone().add(acrossOffset, 0.0D, depthOffset);
        }
        return TableGeometry.add(
            TableGeometry.add(anchor, TableGeometry.offsetAcrossSeat(viewerSeat, acrossOffset)),
            TableGeometry.offsetTowardSeatFront(viewerSeat, depthOffset)
        );
    }

    private static void appendButtonSpecs(
        TableRenderSubject session,
        UUID viewerId,
        TableViewerActionButtonSnapshot button,
        Location labelLocation,
        float buttonWidth,
        float buttonHeight,
        Display.Billboard billboard,
        float yaw,
        TableGeometry.Offset flatAcrossAxis,
        List<DisplayEntities.EntitySpec> specs,
        List<DisplayInteractionRayRegistry.RayInteraction> flatInteractions
    ) {
        specs.add(DisplayEntities.labelSpec(
            labelLocation,
            net.kyori.adventure.text.Component.text("[" + button.label() + "]", button.color()),
            TableRenderConstants.VIEWER_ACTION_BUTTON_BACKGROUND,
            List.of(viewerId),
            billboard,
            yaw,
            0.0F,
            true
        ));
        DisplayClickAction clickAction = DisplayClickAction.playerCommand(session.id(), viewerId, button.command());
        if (billboard == Display.Billboard.FIXED && flatAcrossAxis != null && flatInteractions != null) {
            UUID worldId = labelLocation.getWorld() == null ? null : labelLocation.getWorld().getUID();
            flatInteractions.add(new DisplayInteractionRayRegistry.RayInteraction(
                worldId,
                labelLocation.getX(),
                labelLocation.getY() - TableRenderConstants.LABEL_INTERACTION_Y_OFFSET + buttonHeight / 2.0D,
                labelLocation.getZ(),
                flatAcrossAxis.x(),
                flatAcrossAxis.z(),
                buttonWidth,
                buttonHeight,
                0.0F,
                clickAction
            ));
        }
    }

    private static TableGeometry.Offset viewerActionAcrossAxis(SeatWind viewerSeat) {
        return viewerSeat == null
            ? new TableGeometry.Offset(1.0D, 0.0D)
            : TableGeometry.offsetAcrossSeat(viewerSeat, 1.0D);
    }

    private static double viewerActionRowWidth(List<TableViewerActionButtonSnapshot> buttons) {
        double width = 0.0D;
        for (TableViewerActionButtonSnapshot button : buttons) {
            width += viewerActionButtonWidth(button);
        }
        return width + Math.max(0, buttons.size() - 1) * TableRenderConstants.OVERLAY_ACTION_BUTTON_GAP;
    }

    private static float viewerActionButtonWidth(TableViewerActionButtonSnapshot button) {
        if (button == null) {
            return TableRenderConstants.OVERLAY_ACTION_BUTTON_SPACING;
        }
        float estimated = estimateActionLabelWidth(button.label());
        return Math.max(Math.max(TableRenderConstants.OVERLAY_ACTION_BUTTON_SPACING, button.hitboxWidth()), estimated);
    }

    private static float estimateActionLabelWidth(String label) {
        if (label == null || label.isBlank()) {
            return TableRenderConstants.ACTION_LABEL_MIN_WIDTH;
        }
        int visualUnits = TableFeedbackPolicy.visualUnits(label);
        float estimated = TableRenderConstants.ACTION_LABEL_BASE_WIDTH
            + TableRenderConstants.ACTION_LABEL_DECORATION_WIDTH
            + visualUnits * TableRenderConstants.ACTION_LABEL_WIDTH_PER_UNIT;
        return Math.max(TableRenderConstants.ACTION_LABEL_MIN_WIDTH, Math.min(TableRenderConstants.ACTION_LABEL_MAX_WIDTH, estimated));
    }

    public record ViewerActionOverlayPlan(
        List<DisplayEntities.EntitySpec> entitySpecs,
        List<DisplayInteractionRayRegistry.RayInteraction> flatInteractions
    ) {
        public ViewerActionOverlayPlan {
            entitySpecs = List.copyOf(entitySpecs);
            flatInteractions = List.copyOf(flatInteractions);
        }

        static ViewerActionOverlayPlan empty() {
            return new ViewerActionOverlayPlan(List.of(), List.of());
        }
    }
}
