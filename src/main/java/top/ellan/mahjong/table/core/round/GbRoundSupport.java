package top.ellan.mahjong.table.core.round;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.model.SeatWind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class GbRoundSupport {
    private GbRoundSupport() {
    }

    static String relationLabel(SeatWind claimant, SeatWind source) {
        int diff = Math.floorMod(source.index() - claimant.index(), SeatWind.values().length);
        return switch (diff) {
            case 1 -> "LEFT";
            case 2 -> "ACROSS";
            case 3 -> "RIGHT";
            default -> "SELF";
        };
    }

    static boolean canChii(SeatWind candidate, SeatWind discarder) {
        return candidate == SeatWind.fromIndex(Math.floorMod(discarder.index() + 1, SeatWind.values().length));
    }

    static List<SeatWind> orderedAfter(SeatWind start) {
        List<SeatWind> winds = new ArrayList<>(SeatWind.values().length - 1);
        for (int offset = 1; offset < SeatWind.values().length; offset++) {
            winds.add(SeatWind.fromIndex(Math.floorMod(start.index() + offset, SeatWind.values().length)));
        }
        return List.copyOf(winds);
    }

    static String normalizeTileToken(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            MahjongTile.valueOf(upper);
            return upper;
        } catch (IllegalArgumentException ignored) {
            // Continue to shorthand parsing below.
        }

        String compact = trimmed.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (compact.length() < 2 || compact.length() > 3) {
            return upper;
        }
        char first = compact.charAt(0);
        char second = compact.charAt(1);
        Character suit = null;
        Character number = null;
        if (isSuit(first) && Character.isDigit(second)) {
            suit = first;
            number = second;
        } else if (Character.isDigit(first) && isSuit(second)) {
            number = first;
            suit = second;
        }
        if (suit == null || number == null) {
            return upper;
        }
        boolean red = compact.endsWith("r");
        int numeric = number == '0' ? 5 : Character.digit(number, 10);
        if (numeric < 1 || numeric > 9) {
            return upper;
        }
        return ("" + Character.toUpperCase(suit) + numeric) + (red || number == '0' ? "_RED" : "");
    }

    private static boolean isSuit(char value) {
        return value == 'm' || value == 'p' || value == 's';
    }

    static boolean containsTile(List<MahjongTile> hand, MahjongTile target) {
        return countMatchingTiles(hand, target) > 0;
    }

    static int countMatchingTiles(List<MahjongTile> hand, MahjongTile target) {
        if (hand == null || target == null) {
            return 0;
        }
        int count = 0;
        for (MahjongTile tile : hand) {
            if (sameKind(tile, target)) {
                count++;
            }
        }
        return count;
    }

    static void removeTiles(List<MahjongTile> hand, MahjongTile target, int amount) {
        int remaining = amount;
        for (int i = hand.size() - 1; i >= 0 && remaining > 0; i--) {
            if (sameKind(hand.get(i), target)) {
                hand.remove(i);
                remaining--;
            }
        }
    }

    static boolean sameKind(MahjongTile left, MahjongTile right) {
        if (left == null || right == null) {
            return false;
        }
        MahjongTile leftBase = left.isRedFive() ? MahjongTile.valueOf(left.name().replace("_RED", "")) : left;
        MahjongTile rightBase = right.isRedFive() ? MahjongTile.valueOf(right.name().replace("_RED", "")) : right;
        return leftBase == rightBase;
    }

    // Precomputed lookup tables indexed by MahjongTile.ordinal(). Eliminates the
    // per-call String allocation (name().substring(1,2)) and Integer.parseInt
    // parsing inside hot loops such as GbBotDecisionService.discardPreference.
    private static final int[] TILE_NUMBERS = buildTileNumbers();
    private static final boolean[] TILE_HONORS = buildTileHonors();

    private static int[] buildTileNumbers() {
        MahjongTile[] values = MahjongTile.values();
        int[] numbers = new int[values.length];
        int east = MahjongTile.EAST.ordinal();
        int redDragon = MahjongTile.RED_DRAGON.ordinal();
        for (MahjongTile tile : values) {
            int ord = tile.ordinal();
            if (tile.isFlower() || (ord >= east && ord <= redDragon)) {
                numbers[ord] = 0;
                continue;
            }
            // UNKNOWN and any other non-suited tile would fail parseInt; leave 0.
            // Callers never pass such tiles to tileNumber() (they guard with
            // isFlower()/isHonor() or only pass suited tiles from hands/walls).
            try {
                numbers[ord] = Integer.parseInt(tile.name().substring(1, 2));
            } catch (NumberFormatException ignored) {
                numbers[ord] = 0;
            }
        }
        return numbers;
    }

    private static boolean[] buildTileHonors() {
        MahjongTile[] values = MahjongTile.values();
        boolean[] honors = new boolean[values.length];
        int east = MahjongTile.EAST.ordinal();
        int redDragon = MahjongTile.RED_DRAGON.ordinal();
        for (int i = 0; i < values.length; i++) {
            honors[i] = i >= east && i <= redDragon;
        }
        return honors;
    }

    static boolean isHonor(MahjongTile tile) {
        return TILE_HONORS[tile.ordinal()];
    }

    static int tileNumber(MahjongTile tile) {
        if (tile == null || tile.isFlower() || isHonor(tile)) {
            return 0;
        }
        return TILE_NUMBERS[tile.ordinal()];
    }

    static MahjongTile offsetTile(MahjongTile tile, int delta) {
        if (tile == null || isHonor(tile) || tile.isFlower()) {
            return MahjongTile.UNKNOWN;
        }
        char suit = tile.name().charAt(0);
        int number = tileNumber(tile) + delta;
        if (number < 1 || number > 9) {
            return MahjongTile.UNKNOWN;
        }
        return MahjongTile.valueOf("" + suit + number);
    }

    // Direct enum-to-enum mapping avoids the expensive valueOf(name()) string
    // round-trip on every tile conversion between the model and riichi types.
    // Both enums share identical constant names, so the maps are built once.
    private static final Map<MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> TO_RIICHI_MAP = buildToRiichiMap();
    private static final Map<top.ellan.mahjong.riichi.model.MahjongTile, MahjongTile> FROM_RIICHI_MAP = buildFromRiichiMap();

    private static Map<MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> buildToRiichiMap() {
        Map<MahjongTile, top.ellan.mahjong.riichi.model.MahjongTile> map = new EnumMap<>(MahjongTile.class);
        for (MahjongTile tile : MahjongTile.values()) {
            map.put(tile, top.ellan.mahjong.riichi.model.MahjongTile.valueOf(tile.name()));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<top.ellan.mahjong.riichi.model.MahjongTile, MahjongTile> buildFromRiichiMap() {
        Map<top.ellan.mahjong.riichi.model.MahjongTile, MahjongTile> map = new EnumMap<>(top.ellan.mahjong.riichi.model.MahjongTile.class);
        for (top.ellan.mahjong.riichi.model.MahjongTile tile : top.ellan.mahjong.riichi.model.MahjongTile.values()) {
            map.put(tile, MahjongTile.valueOf(tile.name()));
        }
        return Collections.unmodifiableMap(map);
    }

    static top.ellan.mahjong.riichi.model.MahjongTile toRiichiTile(MahjongTile tile) {
        return TO_RIICHI_MAP.get(tile);
    }

    static MahjongTile fromRiichiTile(top.ellan.mahjong.riichi.model.MahjongTile tile) {
        return FROM_RIICHI_MAP.get(tile);
    }

    static List<top.ellan.mahjong.riichi.model.MahjongTile> toRiichiTiles(List<MahjongTile> tiles) {
        List<top.ellan.mahjong.riichi.model.MahjongTile> converted = new ArrayList<>(tiles.size());
        for (MahjongTile tile : tiles) {
            converted.add(toRiichiTile(tile));
        }
        return List.copyOf(converted);
    }

    static int requireValidDicePoints(int value) {
        if (value < 2 || value > 12) {
            throw new IllegalStateException("Dice points must be between 2 and 12 but was " + value);
        }
        return value;
    }

    static int rollDicePoints() {
        return ThreadLocalRandom.current().nextInt(1, 7) + ThreadLocalRandom.current().nextInt(1, 7);
    }

    static List<MahjongTile> buildWall() {
        return buildWall(GbRuleProfile.GB);
    }

    static List<MahjongTile> buildWall(GbRuleProfile profile) {
        GbRuleProfile safeProfile = profile == null ? GbRuleProfile.GB : profile;
        int initialCapacity = safeProfile.includesHonors() && safeProfile.includesFlowers() ? 144 : 108;
        List<MahjongTile> wall = new ArrayList<>(initialCapacity);
        for (MahjongTile tile : MahjongTile.values()) {
            if (tile == MahjongTile.UNKNOWN || tile.isRedFive()) {
                continue;
            }
            if (!safeProfile.includesHonors() && isHonor(tile)) {
                continue;
            }
            if (!safeProfile.includesFlowers() && tile.isFlower()) {
                continue;
            }
            int copies = tile.isFlower() ? 1 : 4;
            for (int i = 0; i < copies; i++) {
                wall.add(tile);
            }
        }
        Collections.shuffle(wall);
        return List.copyOf(wall);
    }

    static List<MahjongTile> reorderWallForDice(List<MahjongTile> wall, int dicePoints, int dealerIndex) {
        return reorderWallForDice(wall, dicePoints, dicePoints, dealerIndex);
    }

    static List<MahjongTile> reorderWallForDice(List<MahjongTile> wall, int directionDicePoints, int breakDicePoints, int dealerIndex) {
        if (wall == null || wall.isEmpty()) {
            return List.of();
        }
        int seatCount = SeatWind.values().length;
        int wallTilesPerSide = wall.size() / seatCount;
        int openDoorIndex = Math.floorMod(dealerIndex + directionDicePoints - 1, seatCount);
        int startingStackIndex = 2 * (directionDicePoints + breakDicePoints);
        List<MahjongTile> reordered = new ArrayList<>(wall.size());
        for (int i = 0; i < wall.size(); i++) {
            int tileIndex = Math.floorMod(openDoorIndex * wallTilesPerSide + startingStackIndex + i, wall.size());
            reordered.add(wall.get(tileIndex));
        }
        return List.copyOf(reordered);
    }

    static List<MahjongTile> reorderSichuanWallForDice(List<MahjongTile> wall, int dicePoints, int smallerDie, int dealerIndex) {
        if (wall == null || wall.isEmpty()) {
            return List.of();
        }
        int seatCount = SeatWind.values().length;
        int wallTilesPerSide = wall.size() / seatCount;
        int openDoorIndex = Math.floorMod(dealerIndex + dicePoints - 1, seatCount);
        int startingTileIndex = 2 * Math.max(1, Math.min(6, smallerDie));
        List<MahjongTile> reordered = new ArrayList<>(wall.size());
        for (int i = 0; i < wall.size(); i++) {
            int tileIndex = Math.floorMod(openDoorIndex * wallTilesPerSide + startingTileIndex + i, wall.size());
            reordered.add(wall.get(tileIndex));
        }
        return List.copyOf(reordered);
    }
}
