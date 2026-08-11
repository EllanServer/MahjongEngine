package top.ellan.mahjong.presentation.asset;

import java.util.Objects;
import top.ellan.mahjong.spi.TileVisualId;

/** Extracts the canonical shared-asset suffix already selected by a rule pack. */
public final class TileAssetName {
    private static final String TILE_SEGMENT = ":tile/";

    private TileAssetName() {}

    public static String from(TileVisualId visualId) {
        String value = Objects.requireNonNull(visualId, "visualId").value();
        int separator = value.indexOf(TILE_SEGMENT);
        if (separator < 1 || separator + TILE_SEGMENT.length() >= value.length()) {
            throw new IllegalArgumentException("Unsupported tile visual id: " + value);
        }
        String name = value.substring(separator + TILE_SEGMENT.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if ((character < 'a' || character > 'z')
                    && (character < '0' || character > '9')
                    && character != '_') {
                throw new IllegalArgumentException("Unsupported tile visual id: " + value);
            }
        }
        return name;
    }
}
