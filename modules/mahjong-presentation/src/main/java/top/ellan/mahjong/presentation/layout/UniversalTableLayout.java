package top.ellan.mahjong.presentation.layout;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import top.ellan.mahjong.spi.RuleTablePresentation;

/**
 * Resolves every ruleset through the same bounded, precomputed physical layout cache.
 *
 * <p>The public facade owns cache policy only. Geometry compilation and frame lookup live in
 * dedicated collaborators so this class cannot grow back into a presentation controller.</p>
 */
public final class UniversalTableLayout implements TableLayout {
    private static final int PLAN_CACHE_SIZE = 8;

    private final TableGeometry geometry;
    private final AtomicReferenceArray<CacheEntry> plans =
            new AtomicReferenceArray<>(PLAN_CACHE_SIZE);
    private final AtomicInteger nextPlanSlot = new AtomicInteger();

    public UniversalTableLayout(TableGeometry geometry) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    @Override
    public ResolvedTableLayout resolve(RuleTablePresentation tablePresentation) {
        Objects.requireNonNull(tablePresentation, "tablePresentation");
        UniversalLayoutSpec spec = UniversalLayoutSpec.from(tablePresentation);
        UniversalLayoutPlan plan = cached(spec);
        return new ResolvedUniversalTableLayout(
                plan,
                tablePresentation.wall().drawStartStack(),
                tablePresentation.wall().direction());
    }

    private UniversalLayoutPlan cached(UniversalLayoutSpec spec) {
        for (int slot = 0; slot < PLAN_CACHE_SIZE; slot++) {
            CacheEntry cached = plans.get(slot);
            if (cached != null && cached.spec().equals(spec)) {
                return cached.plan();
            }
        }
        UniversalLayoutPlan compiled = new UniversalLayoutCompiler(geometry, spec).compile();
        int cacheSlot = Math.floorMod(nextPlanSlot.getAndIncrement(), PLAN_CACHE_SIZE);
        plans.set(cacheSlot, new CacheEntry(spec, compiled));
        return compiled;
    }

    private record CacheEntry(UniversalLayoutSpec spec, UniversalLayoutPlan plan) {}
}
