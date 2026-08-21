package top.ellan.mahjong.craftengine.privateview;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.CommonConditionType;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.condition.ConditionFactory;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;
import top.ellan.mahjong.presentation.node.SceneVisibility;

/**
 * Thread-safe audience source for CE's per-player conditional furniture elements.
 *
 * <p>The condition is registered before the bundled CE configuration is installed. Missing state
 * always resolves to hidden, so reload/startup races cannot expose a private tile face.</p>
 */
public final class PrivateFurnitureVisibility implements AutoCloseable {
    public static final String CONDITION_TYPE = "mahjongpaper:private_viewer";
    private static final Key CONDITION_KEY =
            Key.fromNamespaceAndPath("mahjongpaper", "private_viewer");

    private final ConcurrentMap<UUID, Set<UUID>> viewersByFurniture;

    public PrivateFurnitureVisibility() {
        viewersByFurniture = registerOrReuseCondition();
        viewersByFurniture.clear();
    }

    public void authorize(BukkitFurniture furniture, SceneVisibility visibility) {
        Objects.requireNonNull(furniture, "furniture");
        authorizeIds(furniture.uuid(), visibility);
        refreshTrackedViewers(furniture);
    }

    Set<UUID> authorizeIds(UUID furnitureId, SceneVisibility visibility) {
        Objects.requireNonNull(furnitureId, "furnitureId");
        Objects.requireNonNull(visibility, "visibility");
        if (visibility.isPublic() || visibility.viewers().isEmpty()) {
            throw new IllegalArgumentException("Private CE furniture requires a private audience");
        }
        HashSet<UUID> viewers = HashSet.newHashSet(visibility.viewers().size());
        visibility.viewers().forEach(viewer -> viewers.add(viewer.value()));
        Set<UUID> audience = Set.copyOf(viewers);
        viewersByFurniture.put(furnitureId, audience);
        return audience;
    }

    public void revoke(BukkitFurniture furniture) {
        if (furniture == null) {
            return;
        }
        viewersByFurniture.remove(furniture.uuid());
        refreshTrackedViewers(furniture);
    }

    /** Drops audience state after CE has already invalidated an unloading instance. */
    public void forget(BukkitFurniture furniture) {
        if (furniture != null) {
            viewersByFurniture.remove(furniture.uuid());
        }
    }

    public boolean canView(UUID furnitureId, UUID playerId) {
        Set<UUID> viewers = viewersByFurniture.get(furnitureId);
        return viewers != null && viewers.contains(playerId);
    }

    @Override
    public void close() {
        viewersByFurniture.clear();
    }

    private static ConcurrentMap<UUID, Set<UUID>> registerOrReuseCondition() {
        synchronized (BuiltInRegistries.COMMON_CONDITION_TYPE) {
            CommonConditionType<?> existing =
                    BuiltInRegistries.COMMON_CONDITION_TYPE.getValue(CONDITION_KEY);
            if (existing != null) {
                return audienceMap(existing.factory());
            }
            ConcurrentMap<UUID, Set<UUID>> audiences = new ConcurrentHashMap<>();
            CommonConditions.register(CONDITION_KEY, new ViewerConditionFactory(audiences));
            return audiences;
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<UUID, Set<UUID>> audienceMap(Object factory) {
        if (factory instanceof ViewerConditionFactory viewerFactory) {
            return viewerFactory.audiences();
        }
        try {
            Object audiences = factory.getClass().getMethod("mahjongAudienceRegistry").invoke(factory);
            if (audiences instanceof ConcurrentMap<?, ?> map) {
                return (ConcurrentMap<UUID, Set<UUID>>) map;
            }
        } catch (IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException ignored) {
            // A key owned by anything but an older MahjongPaper class loader is incompatible.
        }
        throw new IllegalStateException(
                "CraftEngine condition key is already owned by an incompatible extension: "
                        + CONDITION_TYPE);
    }

    private static void refreshTrackedViewers(BukkitFurniture furniture) {
        for (Player tracked : furniture.trackedBy()) {
            // CE's show path only emits elements whose condition is true; explicitly hiding first
            // removes elements that were admitted by the previous audience snapshot.
            furniture.hide(tracked);
            furniture.show(tracked);
        }
    }

    /** Stable CE-owned factory bridge; its JDK-only map can be reused after plugin reload. */
    public static final class ViewerConditionFactory
            implements ConditionFactory<Context, ViewerCondition> {
        private final ConcurrentMap<UUID, Set<UUID>> audiences;

        private ViewerConditionFactory(ConcurrentMap<UUID, Set<UUID>> audiences) {
            this.audiences = audiences;
        }

        @Override
        public ViewerCondition create(ConfigSection ignored) {
            return new ViewerCondition(audiences);
        }

        public ConcurrentMap<UUID, Set<UUID>> mahjongAudienceRegistry() {
            return audiences;
        }

        private ConcurrentMap<UUID, Set<UUID>> audiences() {
            return audiences;
        }
    }

    public static final class ViewerCondition implements Condition<Context> {
        private final ConcurrentMap<UUID, Set<UUID>> audiences;

        private ViewerCondition(ConcurrentMap<UUID, Set<UUID>> audiences) {
            this.audiences = audiences;
        }

        @Override
        public boolean test(Context context) {
            Furniture furniture = context
                    .getOptionalParameter(DirectContextParameters.FURNITURE)
                    .orElse(null);
            Player player = context
                    .getOptionalParameter(DirectContextParameters.PLAYER)
                    .orElse(null);
            if (furniture == null || player == null) {
                return false;
            }
            Set<UUID> viewers = audiences.get(furniture.uuid());
            return viewers != null && viewers.contains(player.uuid());
        }
    }
}
