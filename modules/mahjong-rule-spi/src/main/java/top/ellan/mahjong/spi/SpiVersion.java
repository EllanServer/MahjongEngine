package top.ellan.mahjong.spi;

import java.util.Set;

/** Version of the parent-classloader contract shared by the core and rule packs. */
public final class SpiVersion {
    public static final String CURRENT = "1.5.0";
    private static final Set<String> SUPPORTED = Set.of("1.4.0", CURRENT);

    private SpiVersion() {}

    /** Old 1.4 packs remain loadable for pinned recovery and inherit empty automation support. */
    public static boolean isSupported(String version) {
        return SUPPORTED.contains(version);
    }
}
