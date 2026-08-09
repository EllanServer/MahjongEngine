package top.ellan.mahjong.spi;

import java.util.Set;

/** Version of the parent-classloader contract shared by the core and rule packs. */
public final class SpiVersion {
    public static final String CURRENT = "1.6.0";
    private static final Set<String> SUPPORTED = Set.of("1.4.0", "1.5.0", CURRENT);

    private SpiVersion() {}

    /** Older packs remain loadable for pinned recovery and inherit optional newer capabilities. */
    public static boolean isSupported(String version) {
        return SUPPORTED.contains(version);
    }
}
