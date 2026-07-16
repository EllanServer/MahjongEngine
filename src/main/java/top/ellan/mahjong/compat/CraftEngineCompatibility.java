package top.ellan.mahjong.compat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Checks the CraftEngine runtime without linking MahjongPaper's direct CraftEngine bridges.
 *
 * <p>This class deliberately refers to CraftEngine API types by name. It must remain safe to
 * load when CraftEngine is missing or exposes an incompatible API, because the check runs before
 * {@link CraftEngineService} constructs the direct bridge classes.
 */
public final class CraftEngineCompatibility {
    public static final String MINIMUM_VERSION = "26.7";

    private static final Pattern NUMERIC_VERSION = Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d+)+)");
    private static final NumericVersion MINIMUM_NUMERIC_VERSION =
        NumericVersion.parse(MINIMUM_VERSION).orElseThrow();

    private CraftEngineCompatibility() {
    }

    /**
     * Reads the installed plugin version and probes the public API used by the direct bridges.
     */
    public static Result inspect(PluginManager pluginManager) {
        Objects.requireNonNull(pluginManager, "pluginManager");
        Plugin craftEngine = pluginManager.getPlugin("CraftEngine");
        if (craftEngine == null) {
            return Result.failure(
                null,
                "CraftEngine was not found. MahjongPaper requires CraftEngine "
                    + MINIMUM_VERSION
                    + " or newer; install and enable it before starting or reloading MahjongPaper."
            );
        }

        String installedVersion = Objects.toString(craftEngine.getPluginMeta().getVersion(), "").trim();
        return inspectInstalled(installedVersion, craftEngine.isEnabled(), craftEngine.getClass().getClassLoader());
    }

    static Result inspectInstalled(String installedVersion, boolean enabled, ClassLoader classLoader) {
        String displayedVersion = installedVersion == null || installedVersion.isBlank()
            ? "(unknown)"
            : installedVersion.trim();
        Optional<NumericVersion> parsedVersion = NumericVersion.parse(installedVersion);
        if (parsedVersion.isEmpty()) {
            return Result.failure(
                displayedVersion,
                "CraftEngine version '"
                    + displayedVersion
                    + "' cannot be checked. MahjongPaper requires a numeric CraftEngine version of "
                    + MINIMUM_VERSION
                    + " or newer (for example, 26.7 or 26.7.1)."
            );
        }
        if (parsedVersion.orElseThrow().compareTo(MINIMUM_NUMERIC_VERSION) < 0) {
            return Result.failure(
                displayedVersion,
                "CraftEngine "
                    + displayedVersion
                    + " is incompatible with MahjongPaper; CraftEngine "
                    + MINIMUM_VERSION
                    + " or newer is required."
            );
        }
        if (!enabled) {
            return Result.failure(
                displayedVersion,
                "CraftEngine "
                    + displayedVersion
                    + " is installed but not enabled. MahjongPaper requires an enabled CraftEngine "
                    + MINIMUM_VERSION
                    + " or newer before startup or reload."
            );
        }
        if (classLoader == null) {
            return Result.failure(
                displayedVersion,
                incompatibleApiMessage(displayedVersion, "the CraftEngine plugin class loader is unavailable")
            );
        }

        String apiFailure = probePublicApi(classLoader);
        if (apiFailure != null) {
            return Result.failure(displayedVersion, incompatibleApiMessage(displayedVersion, apiFailure));
        }
        return Result.compatible(displayedVersion);
    }

    static String probePublicApi(ClassLoader classLoader) {
        try {
            Class<?> items = apiClass(classLoader, "net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            Class<?> itemDefinition = apiClass(
                classLoader,
                "net.momirealms.craftengine.bukkit.item.BukkitItemDefinition"
            );
            requireMethod(items, true, "byId", itemDefinition, String.class);
            requireMethod(itemDefinition, false, "buildBukkitItem", ItemStack.class);

            Class<?> key = apiClass(classLoader, "net.momirealms.craftengine.core.util.Key");
            Class<?> furnitureApi = apiClass(
                classLoader,
                "net.momirealms.craftengine.bukkit.api.CraftEngineFurniture"
            );
            Class<?> bukkitFurniture = apiClass(
                classLoader,
                "net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture"
            );
            Class<?> worldPosition = apiClass(
                classLoader,
                "net.momirealms.craftengine.core.world.WorldPosition"
            );
            Class<?> furnitureHitBox = apiClass(
                classLoader,
                "net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox"
            );
            Class<?> seat = apiClass(classLoader, "net.momirealms.craftengine.core.entity.seat.Seat");
            requireMethod(key, true, "of", key, String.class);
            requireMethod(furnitureApi, true, "place", bukkitFurniture, Location.class, key);
            requireMethod(furnitureApi, true, "isFurniture", boolean.class, Entity.class);
            requireMethod(furnitureApi, true, "isSeat", boolean.class, Entity.class);
            requireMethod(furnitureApi, true, "remove", boolean.class, Entity.class, boolean.class, boolean.class);
            requireMethod(
                furnitureApi,
                true,
                "getLoadedFurnitureByMetaEntity",
                bukkitFurniture,
                Entity.class
            );
            requireMethod(furnitureApi, true, "getLoadedFurnitureBySeat", bukkitFurniture, Entity.class);
            requireMethod(bukkitFurniture, false, "bukkitEntity", Entity.class);
            requireMethod(bukkitFurniture, false, "entityId", int.class);
            requireMethod(bukkitFurniture, false, "id", key);
            requireMethod(bukkitFurniture, false, "hitboxes", List.class);
            requireMethod(bukkitFurniture, false, "position", worldPosition);
            requireMethod(furnitureHitBox, false, "seats", seat.arrayType());
            requireMethod(seat, false, "isOccupied", boolean.class);

            Class<?> corePlayer = apiClass(
                classLoader,
                "net.momirealms.craftengine.core.entity.player.Player"
            );
            requireMethod(seat, false, "spawnSeat", boolean.class, corePlayer, worldPosition);

            Class<?> bukkitAdaptor = apiClass(
                classLoader,
                "net.momirealms.craftengine.bukkit.api.BukkitAdaptor"
            );
            Class<?> bukkitServerPlayer = apiClass(
                classLoader,
                "net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer"
            );
            Class<?> cullable = apiClass(
                classLoader,
                "net.momirealms.craftengine.core.entity.culling.Cullable"
            );
            Class<?> cullingData = apiClass(
                classLoader,
                "net.momirealms.craftengine.core.entity.culling.CullingData"
            );
            Class<?> aabb = apiClass(
                classLoader,
                "net.momirealms.craftengine.core.world.collision.AABB"
            );
            requireMethod(
                bukkitAdaptor,
                true,
                "adapt",
                bukkitServerPlayer,
                org.bukkit.entity.Player.class
            );
            requireMethod(corePlayer, false, "platformPlayer", Object.class);
            requireMethod(corePlayer, false, "addTrackedEntity", void.class, int.class, cullable);
            requireMethod(corePlayer, false, "removeTrackedEntity", void.class, int.class);
            requireMethod(cullable, false, "show", void.class, corePlayer);
            requireMethod(cullable, false, "hide", void.class, corePlayer);
            requireMethod(cullable, false, "cullingData", cullingData);
            requireConstructor(
                aabb,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class
            );
            requireConstructor(cullingData, aabb, int.class, double.class, boolean.class);

            requireFurnitureEventApi(classLoader, "FurnitureBreakEvent", bukkitFurniture, false);
            requireFurnitureEventApi(classLoader, "FurnitureHitEvent", bukkitFurniture, false);
            requireFurnitureEventApi(classLoader, "FurnitureInteractEvent", bukkitFurniture, true);
            return null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException exception) {
            return describeFailure(exception);
        }
    }

    private static void requireFurnitureEventApi(
        ClassLoader classLoader,
        String simpleName,
        Class<?> bukkitFurniture,
        boolean requirePlayer
    ) throws ReflectiveOperationException {
        Class<?> event = apiClass(
            classLoader,
            "net.momirealms.craftengine.bukkit.api.event." + simpleName
        );
        requireMethod(event, false, "furniture", bukkitFurniture);
        requireMethod(event, false, "setCancelled", void.class, boolean.class);
        if (requirePlayer) {
            requireMethod(event, false, "player", org.bukkit.entity.Player.class);
        }
    }

    private static Class<?> apiClass(ClassLoader classLoader, String className) throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    private static void requireConstructor(Class<?> owner, Class<?>... parameterTypes)
        throws NoSuchMethodException {
        owner.getConstructor(parameterTypes);
    }

    private static void requireMethod(
        Class<?> owner,
        boolean expectedStatic,
        String name,
        Class<?> expectedReturnType,
        Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        boolean actualStatic = Modifier.isStatic(method.getModifiers());
        if (actualStatic != expectedStatic || method.getReturnType() != expectedReturnType) {
            throw new NoSuchMethodException(
                "Expected "
                    + (expectedStatic ? "static " : "instance ")
                    + owner.getName()
                    + '#'
                    + name
                    + " returning "
                    + expectedReturnType.getTypeName()
            );
        }
    }

    private static String incompatibleApiMessage(String installedVersion, String detail) {
        return "CraftEngine "
            + installedVersion
            + " reports a compatible version, but its required 26.7 public API is unavailable ("
            + detail
            + "). Install an official CraftEngine "
            + MINIMUM_VERSION
            + " or newer build before starting or reloading MahjongPaper.";
    }

    private static String describeFailure(Throwable failure) {
        String message = Objects.toString(failure.getMessage(), "").trim();
        return message.isEmpty()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }

    static final class NumericVersion implements Comparable<NumericVersion> {
        private final List<Integer> segments;

        private NumericVersion(List<Integer> segments) {
            this.segments = List.copyOf(segments);
        }

        static Optional<NumericVersion> parse(String rawVersion) {
            if (rawVersion == null || rawVersion.isBlank()) {
                return Optional.empty();
            }
            Matcher matcher = NUMERIC_VERSION.matcher(rawVersion.trim());
            if (!matcher.find()) {
                return Optional.empty();
            }

            String[] rawSegments = matcher.group(1).split("\\.");
            List<Integer> parsedSegments = new ArrayList<>(rawSegments.length);
            try {
                for (String segment : rawSegments) {
                    parsedSegments.add(Integer.parseInt(segment));
                }
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
            return Optional.of(new NumericVersion(parsedSegments));
        }

        @Override
        public int compareTo(NumericVersion other) {
            int segmentCount = Math.max(this.segments.size(), other.segments.size());
            for (int index = 0; index < segmentCount; index++) {
                int left = index < this.segments.size() ? this.segments.get(index) : 0;
                int right = index < other.segments.size() ? other.segments.get(index) : 0;
                int comparison = Integer.compare(left, right);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }
    }

    public record Result(boolean compatible, String installedVersion, String failureMessage) {
        private static Result compatible(String installedVersion) {
            return new Result(true, installedVersion, null);
        }

        private static Result failure(String installedVersion, String failureMessage) {
            return new Result(false, installedVersion, Objects.requireNonNull(failureMessage, "failureMessage"));
        }

        public String directBridgeFailure(LinkageError failure) {
            return "CraftEngine "
                + Objects.toString(this.installedVersion, "(unknown)")
                + " could not initialize MahjongPaper's direct API bridge despite passing the 26.7 probe ("
                + describeFailure(failure)
                + "). Install an official CraftEngine "
                + MINIMUM_VERSION
                + " or newer build before starting or reloading MahjongPaper.";
        }
    }
}
