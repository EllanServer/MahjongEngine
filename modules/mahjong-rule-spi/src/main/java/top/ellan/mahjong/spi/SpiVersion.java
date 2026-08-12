package top.ellan.mahjong.spi;

import java.util.Set;

/** Version of the parent-classloader contract shared by the core and rule packs. */
public final class SpiVersion {
    /** Current rule-pack SPI version implemented by the core. */
    public static final String CURRENT = "1.6.0";
    private static final Set<String> SUPPORTED = Set.of("1.4.0", "1.5.0", CURRENT);

    private SpiVersion() {}

    /**
     * Tests whether a rule-pack SPI version is supported for loading or pinned recovery.
     *
     * @param version SPI version declared by the rule pack
     * @return {@code true} when the version is supported
     */
    public static boolean isSupported(String version) {
        return SUPPORTED.contains(version);
    }
}
