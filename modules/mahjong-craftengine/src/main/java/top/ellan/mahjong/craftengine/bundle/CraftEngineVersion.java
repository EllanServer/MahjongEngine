package top.ellan.mahjong.craftengine.bundle;

import java.util.regex.Pattern;

/** Hard compatibility gate for the CE 26.8 API used by the adapter. */
public final class CraftEngineVersion {
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+].*)?");

    private CraftEngineVersion() {}

    public static void requireSupported(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalStateException("Cannot determine CraftEngine version: " + version);
        }
        String[] parts = version.split("[-+]", 2)[0].split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if (major != 26 || minor != 8) {
            throw new IllegalStateException(
                    "MahjongPaper requires the CraftEngine 26.8 API line, found " + version);
        }
    }
}
