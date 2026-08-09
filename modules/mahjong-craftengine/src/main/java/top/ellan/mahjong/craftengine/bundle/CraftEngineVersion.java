package top.ellan.mahjong.craftengine.bundle;

/** Hard compatibility gate for CraftEngine 26.7+. */
public final class CraftEngineVersion {
    private CraftEngineVersion() {}

    public static void requireSupported(String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-+].*)?")) {
            throw new IllegalStateException("Cannot determine CraftEngine version: " + version);
        }
        String[] parts = version.split("[-+]", 2)[0].split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if (major < 26 || major == 26 && minor < 7) {
            throw new IllegalStateException("MahjongPaper requires CraftEngine 26.7 or newer");
        }
    }
}
