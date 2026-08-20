package top.ellan.mahjong.spi;

/** Version of the parent-classloader contract shared by the core and rule packs. */
public final class SpiVersion {
    /** Current rule-pack SPI version implemented by the core. */
    public static final String CURRENT = "1.6.0";

    private SpiVersion() {}

    /**
     * Tests whether a rule-pack SPI version is supported for loading or pinned recovery.
     *
     * <p>Only the current contract is accepted. A pack built against an older SPI must be rebuilt;
     * the core keeps no compatibility shims for superseded contracts.</p>
     *
     * @param version SPI version declared by the rule pack
     * @return {@code true} when the version is supported
     */
    public static boolean isSupported(String version) {
        return CURRENT.equals(version);
    }
}
