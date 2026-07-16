package top.ellan.mahjong.render.scene;

import org.bukkit.Color;

/**
 * Holds the shared rendering constants used by the table scene renderers.
 * All constants are {@code public static final} so the region renderers and
 * geometry helpers can reference them directly.
 */
public final class TableRenderConstants {
    private TableRenderConstants() {
    }

    public static final double ONE_SIXTEENTH = 1.0D / 16.0D;
    public static final double TILE_WIDTH = 0.1125D;
    public static final double TILE_HEIGHT = 0.15D;
    public static final double TILE_DEPTH = 0.075D;
    public static final double TILE_PADDING = 0.0025D;
    public static final double STICK_WIDTH = 0.4D;
    public static final double STICK_HEIGHT = 0.0125D;
    public static final double STICK_DEPTH = 0.0625D;
    public static final double STICK_Y_OFFSET = 0.515625D;
    public static final int STICKS_PER_STACK = 5;
    public static final double TABLE_BOTTOM_SIZE = 14.0D * ONE_SIXTEENTH;
    public static final double TABLE_BOTTOM_HEIGHT = 2.0D * ONE_SIXTEENTH;
    public static final double TABLE_PILLAR_SIZE = 8.0D * ONE_SIXTEENTH;
    public static final double TABLE_PILLAR_HEIGHT = 12.0D * ONE_SIXTEENTH;
    public static final double TABLE_TOP_SIZE_EXPANSION = ONE_SIXTEENTH;
    public static final double TABLE_TOP_THICKNESS = 2.0D * ONE_SIXTEENTH;
    public static final double TABLE_BORDER_THICKNESS = ONE_SIXTEENTH;
    public static final double TABLE_BORDER_OUTWARD_OFFSET = 0.0D;
    public static final double TABLE_BORDER_HEIGHT = 3.0D * ONE_SIXTEENTH;
    public static final double DISPLAY_CENTER_Y_OFFSET = 0.52D;
    public static final double TABLE_VISUAL_Y_OFFSET = 0.375D;
    public static final double FLOATING_TEXT_Y_OFFSET = 1.0D;
    public static final double SEAT_DISTANCE_FROM_HAND_BASE = 0.9D;
    public static final double SEAT_BASE_Y_OFFSET = -0.62D;
    public static final double SEAT_RAISE_Y_OFFSET = 1.0D;
    public static final double SEAT_ANCHOR_Y_OFFSET = -0.18D;
    public static final double SEAT_BASE_WIDTH = 0.72D;
    public static final double SEAT_BASE_HEIGHT = 0.16D;
    public static final double SEAT_BACKREST_WIDTH = 0.72D;
    public static final double SEAT_BACKREST_HEIGHT = 0.72D;
    public static final double SEAT_BACKREST_DEPTH = 0.12D;
    public static final double SEAT_BACKREST_OFFSET = 0.26D;
    public static final double SEAT_CARPET_INSET = 0.08D;
    public static final double SEAT_CARPET_THICKNESS = 0.04D;
    public static final double SEAT_LABEL_DEPTH_OFFSET = 0.03D;
    public static final double SEAT_ACTION_LABEL_Y_OFFSET = -0.64D + FLOATING_TEXT_Y_OFFSET;
    /** Clear horizontal gap between paired ready/unready and leave controls. */
    public static final double SEAT_SIDE_ACTION_GAP = 0.28D;
    public static final float SEAT_ACTION_INTERACTION_HEIGHT = 0.4F;
    public static final float SEAT_ACTION_INTERACTION_MIN_WIDTH = 0.72F;
    public static final float SEAT_ACTION_INTERACTION_MAX_WIDTH = 1.4F;
    public static final double CENTER_LABEL_Y_OFFSET = 0.55D + FLOATING_TEXT_Y_OFFSET - 0.5D;
    public static final float CENTER_LAST_DISCARD_TILE_SCALE = 2.0F;
    /** Keeps the upright last-discard preview visibly suspended above the river. */
    public static final double CENTER_LAST_DISCARD_TILE_Y_OFFSET = 0.68D;
    public static final Color CENTER_LAST_DISCARD_TILE_GLOW = Color.fromRGB(255, 220, 96);
    public static final Color CENTER_LABEL_BACKGROUND = Color.fromARGB(112, 20, 80, 20);
    public static final Color SEAT_ACTION_DEFAULT_BACKGROUND = Color.fromARGB(92, 16, 18, 20);
    public static final Color SEAT_ACTION_JOIN_BACKGROUND = Color.fromARGB(104, 12, 54, 20);
    public static final Color SEAT_ACTION_READY_BACKGROUND = Color.fromARGB(104, 12, 32, 52);
    public static final Color SEAT_ACTION_LEAVE_BACKGROUND = Color.fromARGB(108, 68, 18, 18);
    public static final Color SEAT_LABEL_ACTIVE_BACKGROUND = Color.fromARGB(148, 255, 220, 70);
    public static final Color SEAT_LABEL_EAST_BACKGROUND = Color.fromARGB(132, 255, 183, 0);
    public static final Color SEAT_LABEL_SOUTH_BACKGROUND = Color.fromARGB(132, 72, 217, 92);
    public static final Color SEAT_LABEL_WEST_BACKGROUND = Color.fromARGB(132, 120, 120, 120);
    public static final Color SEAT_LABEL_NORTH_BACKGROUND = Color.fromARGB(132, 86, 148, 255);
    public static final Color VIEWER_OVERLAY_BACKGROUND = Color.fromARGB(84, 12, 12, 12);
    public static final Color SPECTATOR_SEAT_OVERLAY_BACKGROUND = Color.fromARGB(92, 14, 14, 18);
    public static final Color VIEWER_PROMPT_BACKGROUND = Color.fromARGB(72, 20, 18, 4);
    public static final Color VIEWER_ACTION_BUTTON_BACKGROUND = Color.fromARGB(60, 0, 0, 0);
    public static final double WALL_DIRECTION_OFFSET = 1.0D;
    public static final double HAND_DIRECTION_OFFSET = WALL_DIRECTION_OFFSET + TILE_DEPTH + TILE_HEIGHT;
    public static final double HALF_TABLE_LENGTH_NO_BORDER = 0.5D + 15.0D / 16.0D;
    public static final double DEAD_WALL_GAP = TILE_PADDING * 20.0D;
    public static final double WALL_TILE_STEP = TILE_WIDTH + TILE_PADDING;
    public static final double UPRIGHT_TILE_Y = TILE_HEIGHT / 2.0D;
    public static final double FLAT_TILE_Y = TILE_DEPTH / 2.0D;
    public static final float HAND_INTERACTION_WIDTH = (float) TILE_WIDTH;
    public static final float HAND_INTERACTION_HEIGHT = (float) TILE_HEIGHT;
    public static final float SEAT_LABEL_INTERACTION_WIDTH = 1.2F;
    public static final float SEAT_LABEL_INTERACTION_HEIGHT = 0.85F;
    /** Visual-height hit plane for one text row; kept below the 0.24-block row step. */
    public static final float OVERLAY_ACTION_BUTTON_HEIGHT = 0.22F;
    public static final float OVERLAY_ACTION_BUTTON_SPACING = 0.55F;
    public static final float OVERLAY_ACTION_BUTTON_GAP = 0.16F;
    public static final int OVERLAY_ACTION_BUTTONS_PER_ROW = 4;
    public static final double OVERLAY_ACTION_Y_OFFSET = SEAT_ACTION_LABEL_Y_OFFSET;
    /** Decision prompt height above the local action row. */
    public static final double VIEWER_PROMPT_Y_OFFSET = OVERLAY_ACTION_Y_OFFSET + 0.48D;
    public static final int WALL_TILES_PER_SIDE = 34;
    public static final int TOTAL_WALL_TILES = 136;
    public static final int DEAD_WALL_SIZE = 14;
    public static final int LIVE_WALL_SIZE = TOTAL_WALL_TILES - DEAD_WALL_SIZE;
    public static final int DISCARDS_PER_ROW = 6;
    public static final double CUSTOM_FURNITURE_ORIGIN_Y_OFFSET = 1.375D;
    public static final String TABLE_VISUAL_FURNITURE_ID = "mahjongpaper:table_visual";
    public static final String SEAT_VISUAL_FURNITURE_ID = "mahjongpaper:seat_chair";
    public static final String STICK_FURNITURE_PREFIX = "mahjongpaper:stick_";

    // Extracted magic numbers

    /** Across-seat offset used for spectator seat overlays. */
    public static final double SPECTATOR_OVERLAY_ACROSS_OFFSET = 0.42D;
    /** Y offset for spectator seat overlays (added to {@link #FLOATING_TEXT_Y_OFFSET}). */
    public static final double SPECTATOR_OVERLAY_Y_OFFSET = 0.62D;
    /** Y offset for the main viewer overlay label (added to {@link #FLOATING_TEXT_Y_OFFSET}). */
    public static final double VIEWER_OVERLAY_LABEL_Y_OFFSET = 0.9D;
    /** Y offset for the seat status label (added to {@link #FLOATING_TEXT_Y_OFFSET}). */
    public static final double SEAT_STATUS_LABEL_Y_OFFSET = 0.45D;
    /** Per-row vertical step for viewer action buttons. */
    public static final double VIEWER_ACTION_BUTTON_ROW_STEP = 0.24D;
    /** Right edge used by a pinned viewer action when the normal action row is empty. */
    public static final double VIEWER_PINNED_ACTION_EMPTY_RIGHT_EDGE = -0.85D;
    /** Minimum height of the return control above the raw table center. */
    public static final double OVERHEAD_RETURN_BUTTON_MIN_Y_OFFSET = 1.5D;
    /** Minimum width of the centered return control. */
    public static final float OVERHEAD_RETURN_BUTTON_MIN_WIDTH = 1.2F;
    /** Interaction height of the centered return control. */
    public static final float OVERHEAD_RETURN_BUTTON_HEIGHT = 0.65F;
    /** Maximum decision controls in one overhead-view row. */
    public static final int OVERHEAD_ACTION_BUTTONS_PER_ROW = 4;
    /** Clear screen-plane gap between overhead decision rows. */
    public static final double OVERHEAD_ACTION_ROW_GAP = 0.24D;
    /** Extra screen-plane gap before the separate return-to-seat row. */
    public static final double OVERHEAD_RETURN_GROUP_GAP = 0.45D;
    /** Hitbox Y subtract applied to label interactions. */
    public static final double LABEL_INTERACTION_Y_OFFSET = 0.1D;
    /** Per-visual-unit width estimate for action labels. */
    public static final float ACTION_LABEL_WIDTH_PER_UNIT = 0.085F;
    /** Base width used when estimating action label widths. */
    public static final float ACTION_LABEL_BASE_WIDTH = 0.24F;
    /** Extra width occupied by the rendered square brackets around every action label. */
    public static final float ACTION_LABEL_DECORATION_WIDTH = 0.18F;
    /** Minimum estimated action label width. */
    public static final float ACTION_LABEL_MIN_WIDTH = 0.7F;
    /** Maximum estimated action label width. */
    public static final float ACTION_LABEL_MAX_WIDTH = 2.2F;
    /** Safety gap between the pinned river-view hitbox and the nearest decision hitbox. */
    public static final double VIEWER_PINNED_ACTION_GAP = 0.34D;
    /** Base width used when computing seat action interaction widths. */
    public static final float SEAT_ACTION_INTERACTION_BASE_WIDTH = 0.3F;
    /** Per visual unit used when computing seat action interaction widths. */
    public static final float SEAT_ACTION_INTERACTION_PER_VISUAL_UNIT_WIDTH = 0.085F;
    /** Multiplier applied to {@link #TILE_WIDTH} when computing the wall starting position. */
    public static final double WALL_START_POSITION_MULTIPLIER = 17.0D;
}
