package top.ellan.mahjong.craftengine.scene;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorFactory;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorType;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;

/** CE behavior-backed live index and callback bridge for managed Mahjong furniture. */
public final class CraftEngineManagedFurnitureRegistry implements AutoCloseable {
    public static final String BEHAVIOR_TYPE = "mahjongpaper:managed_scene";
    private static final Key BEHAVIOR_KEY =
            Key.fromNamespaceAndPath("mahjongpaper", "managed_scene");

    private final BridgeState state;
    private Consumer<Object[]> lifecycleBridge;
    private Function<Object[], Object> interactionBridge;

    public CraftEngineManagedFurnitureRegistry() {
        state = registerOrReuseBehavior();
    }

    void bindLifecycle(Consumer<Lifecycle> listener) {
        Objects.requireNonNull(listener, "listener");
        Consumer<Object[]> bridge = values -> {
            if (values.length != 2 || !(values[0] instanceof String operation)
                    || !(values[1] instanceof BukkitFurniture furniture)) {
                return;
            }
            ManagedFurnitureIdentity.from(furniture).ifPresent(identity ->
                    listener.accept(new Lifecycle(operation.equals("load"), identity, furniture)));
        };
        lifecycleBridge = bridge;
        state.lifecycle().set(bridge);
    }

    void bindInteraction(Function<Use, InteractionResult> listener) {
        Objects.requireNonNull(listener, "listener");
        Function<Object[], Object> bridge = values -> {
            if (values.length != 2 || !(values[0] instanceof BukkitFurniture furniture)
                    || !(values[1] instanceof InteractEntityContext context)) {
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return ManagedFurnitureIdentity.from(furniture)
                    .map(identity -> listener.apply(new Use(identity, furniture, context)))
                    .orElse(InteractionResult.SUCCESS_AND_CANCEL);
        };
        interactionBridge = bridge;
        state.interaction().set(bridge);
    }

    List<BukkitFurniture> furniture(ManagedFurnitureIdentity identity) {
        ConcurrentMap<UUID, Object> entries = state.loaded().get(identity.semanticKey());
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        ArrayList<BukkitFurniture> result = new ArrayList<>(entries.size());
        entries.values().forEach(value -> {
            if (value instanceof BukkitFurniture furniture) {
                result.add(furniture);
            }
        });
        return List.copyOf(result);
    }

    List<BukkitFurniture> loadedFurniture() {
        ArrayList<BukkitFurniture> result = new ArrayList<>();
        state.loaded().values().forEach(entries -> entries.values().forEach(value -> {
            if (value instanceof BukkitFurniture furniture) {
                result.add(furniture);
            }
        }));
        return List.copyOf(result);
    }

    @Override
    public void close() {
        if (lifecycleBridge != null) {
            state.lifecycle().compareAndSet(lifecycleBridge, null);
            lifecycleBridge = null;
        }
        if (interactionBridge != null) {
            state.interaction().compareAndSet(interactionBridge, null);
            interactionBridge = null;
        }
    }

    private static BridgeState registerOrReuseBehavior() {
        synchronized (BuiltInRegistries.FURNITURE_BEHAVIOR_TYPE) {
            FurnitureBehaviorType<?> existing =
                    BuiltInRegistries.FURNITURE_BEHAVIOR_TYPE.getValue(BEHAVIOR_KEY);
            if (existing == null) {
                BridgeState state = BridgeState.create();
                FurnitureBehaviors.register(BEHAVIOR_KEY, new ManagedFactory(state));
                return state;
            }
            return bridgeState(existing.factory());
        }
    }

    @SuppressWarnings("unchecked")
    private static BridgeState bridgeState(Object factory) {
        if (factory instanceof ManagedFactory managed) {
            return managed.mahjongManagedFurnitureBridge();
        }
        try {
            Object loaded = factory.getClass().getMethod("mahjongLoadedFurniture").invoke(factory);
            Object lifecycle = factory.getClass().getMethod("mahjongLifecycleBridge").invoke(factory);
            Object interaction = factory.getClass().getMethod("mahjongInteractionBridge").invoke(factory);
            if (loaded instanceof ConcurrentMap<?, ?> map
                    && lifecycle instanceof AtomicReference<?> lifecycleReference
                    && interaction instanceof AtomicReference<?> interactionReference) {
                return new BridgeState(
                        (ConcurrentMap<String, ConcurrentMap<UUID, Object>>) map,
                        (AtomicReference<Consumer<Object[]>>) lifecycleReference,
                        (AtomicReference<Function<Object[], Object>>) interactionReference);
            }
        } catch (IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException ignored) {
            // Only a previous MahjongPaper class loader may own this internal CE behavior key.
        }
        throw new IllegalStateException(
                "CraftEngine behavior key is owned by an incompatible extension: " + BEHAVIOR_TYPE);
    }

    record Lifecycle(
            boolean loaded,
            ManagedFurnitureIdentity identity,
            BukkitFurniture furniture) {}

    public record Use(
            ManagedFurnitureIdentity identity,
            BukkitFurniture furniture,
            InteractEntityContext context) {}

    private record BridgeState(
            ConcurrentMap<String, ConcurrentMap<UUID, Object>> loaded,
            AtomicReference<Consumer<Object[]>> lifecycle,
            AtomicReference<Function<Object[], Object>> interaction) {
        private BridgeState {
            Objects.requireNonNull(loaded, "loaded");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(interaction, "interaction");
        }

        static BridgeState create() {
            return new BridgeState(
                    new ConcurrentHashMap<>(), new AtomicReference<>(), new AtomicReference<>());
        }
    }

    /** Public reflection bridge kept in CE's registry across MahjongPaper class-loader reloads. */
    public static final class ManagedFactory
            implements FurnitureBehaviorFactory<ManagedTemplate> {
        private final BridgeState state;

        private ManagedFactory(BridgeState state) {
            this.state = state;
        }

        @Override
        public ManagedTemplate create(FurnitureDefinition furniture, ConfigSection ignored) {
            return new ManagedTemplate(furniture, state);
        }

        public ConcurrentMap<String, ConcurrentMap<UUID, Object>> mahjongLoadedFurniture() {
            return state.loaded();
        }

        public AtomicReference<Consumer<Object[]>> mahjongLifecycleBridge() {
            return state.lifecycle();
        }

        public AtomicReference<Function<Object[], Object>> mahjongInteractionBridge() {
            return state.interaction();
        }

        private BridgeState mahjongManagedFurnitureBridge() {
            return state;
        }
    }

    public static final class ManagedTemplate extends FurnitureBehaviorTemplate {
        private final BridgeState state;

        private ManagedTemplate(FurnitureDefinition furniture, BridgeState state) {
            super(furniture);
            this.state = state;
        }

        @Override
        public FurnitureController createController(Furniture furniture) {
            return new ManagedController(furniture, state);
        }
    }

    private static final class ManagedController extends FurnitureController {
        private final BridgeState state;
        private ManagedFurnitureIdentity identity;

        private ManagedController(Furniture furniture, BridgeState state) {
            super(furniture);
            this.state = state;
        }

        @Override
        public void loadCustomData(CompoundTag data) {
            identity = ManagedFurnitureIdentity.from(data).orElse(null);
        }

        @Override
        public void saveCustomData(CompoundTag data) {
            if (identity != null) {
                identity.writeTo(data);
            }
        }

        @Override
        public void onLoad() {
            if (identity == null) {
                return;
            }
            state.loaded().compute(
                    identity.semanticKey(),
                    (ignored, entries) -> {
                        ConcurrentMap<UUID, Object> current =
                                entries == null ? new ConcurrentHashMap<>() : entries;
                        current.put(furniture.uuid(), furniture);
                        return current;
                    });
            notifyLifecycle("load");
        }

        @Override
        public void onUnload() {
            if (identity == null) {
                return;
            }
            state.loaded().computeIfPresent(
                    identity.semanticKey(),
                    (ignored, entries) -> {
                        entries.remove(furniture.uuid(), furniture);
                        return entries.isEmpty() ? null : entries;
                    });
            notifyLifecycle("unload");
        }

        @Override
        public void onPlace(net.momirealms.craftengine.core.entity.player.Player player) {
            if (identity == null && player != null) {
                CraftEngineFurniture.remove(furniture, player, false, false);
            }
        }

        @Override
        public InteractionResult useOnFurniture(
                FurnitureHitBox hitBox, InteractEntityContext context) {
            if (identity == null) {
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            Function<Object[], Object> callback = state.interaction().get();
            if (callback == null) {
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            try {
                Object result = callback.apply(new Object[] {furniture, context});
                return result instanceof InteractionResult interactionResult
                        ? interactionResult
                        : InteractionResult.SUCCESS_AND_CANCEL;
            } catch (RuntimeException failure) {
                CraftEngine.instance()
                        .logger()
                        .warn("Mahjong managed-furniture interaction failed closed", failure);
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
        }

        @Override
        public InteractionResult onPlayerHit(
                net.momirealms.craftengine.core.entity.player.Player player,
                FurnitureHitBox hitBox) {
            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        @Override
        public Item getItemToPickup(
                net.momirealms.craftengine.core.entity.player.Player player,
                FurnitureHitBox hitBox) {
            return null;
        }

        private void notifyLifecycle(String operation) {
            Consumer<Object[]> callback = state.lifecycle().get();
            if (callback != null) {
                try {
                    callback.accept(new Object[] {operation, furniture});
                } catch (RuntimeException failure) {
                    CraftEngine.instance()
                            .logger()
                            .warn("Mahjong managed-furniture lifecycle callback failed", failure);
                }
            }
        }
    }
}
