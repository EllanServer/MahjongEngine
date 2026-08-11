package top.ellan.mahjong.presentation.node;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** Allocation-free validation cache for the bounded identifiers used by scene nodes. */
final class SceneAssetGrammar {
    private static final int ASSET_CACHE_SIZE = 256;
    private static final int VARIANT_CACHE_SIZE = 64;
    private static final AtomicReferenceArray<String> VALID_ASSETS =
            new AtomicReferenceArray<>(ASSET_CACHE_SIZE);
    private static final AtomicReferenceArray<String> VALID_VARIANTS =
            new AtomicReferenceArray<>(VARIANT_CACHE_SIZE);

    private SceneAssetGrammar() {}

    static boolean validAsset(String value) {
        int slot = slot(value, ASSET_CACHE_SIZE);
        if (value.equals(VALID_ASSETS.get(slot))) {
            return true;
        }
        int separator = value.indexOf(':');
        if (separator <= 0
                || separator == value.length() - 1
                || value.indexOf(':', separator + 1) >= 0
                || !validRange(value, 0, separator, false)
                || !validRange(value, separator + 1, value.length(), true)) {
            return false;
        }
        VALID_ASSETS.lazySet(slot, value);
        return true;
    }

    static boolean validVariant(String value) {
        int slot = slot(value, VARIANT_CACHE_SIZE);
        if (value.equals(VALID_VARIANTS.get(slot))) {
            return true;
        }
        if (value.isEmpty() || !validRange(value, 0, value.length(), false)) {
            return false;
        }
        VALID_VARIANTS.lazySet(slot, value);
        return true;
    }

    private static boolean validRange(String value, int start, int end, boolean slashAllowed) {
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if ((character < 'a' || character > 'z')
                    && (character < '0' || character > '9')
                    && character != '_'
                    && character != '.'
                    && character != '-'
                    && (!slashAllowed || character != '/')) {
                return false;
            }
        }
        return true;
    }

    private static int slot(String value, int cacheSize) {
        int hash = value.hashCode();
        hash ^= hash >>> 16;
        return hash & (cacheSize - 1);
    }
}
