package top.ellan.mahjong.craftengine.scene;

/** Explicit render limits. */
public record CraftEngineBackendConfig(
        int maxMutationsPerTablePerTick,
        long regionBudgetNanos,
        int maxNodesPerTable,
        int maxTablesPerRegion) {
    public static final CraftEngineBackendConfig DEFAULT =
            new CraftEngineBackendConfig(16, 1_500_000, 1_024, 256);

    public CraftEngineBackendConfig {
        if (maxMutationsPerTablePerTick < 1
                || regionBudgetNanos < 1
                || maxNodesPerTable < 1
                || maxTablesPerRegion < 1) {
            throw new IllegalArgumentException("CraftEngine backend limits must be positive");
        }
    }
}
