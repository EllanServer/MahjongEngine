package top.ellan.mahjong.table.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.armorstand.FakeArmorStand;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import top.ellan.mahjong.compat.SparrowFakeEntityFactory;
import top.ellan.mahjong.render.display.ClientInteractionProxyRegistry;
import top.ellan.mahjong.render.display.DisplayInteractionRayRegistry;
import top.ellan.mahjong.table.core.TableSessionContext;

/**
 * Owns coarse, client-only click proxies for exact server-side ray interactions.
 *
 * <p>The invisible armor stands only make vanilla clients send an unknown-entity interaction
 * packet. They never select an action: {@link DisplayInteractionRayRegistry} remains the source
 * of truth for the exact text plane or mahjong tile box.</p>
 */
final class SparrowRayInteractionProxyCoordinator {
    private static final double SMALL_ARMOR_STAND_WIDTH = 0.25D;
    private static final double SMALL_ARMOR_STAND_HEIGHT = 0.9875D;
    private static final double ARMOR_STAND_WIDTH = 0.5D;
    private static final double ARMOR_STAND_HEIGHT = 1.975D;
    private static final int MAX_COLUMNS_PER_INTERACTION = 16;
    private static final int MAX_ROWS_PER_INTERACTION = 4;
    private static final Backend DEFAULT_BACKEND = new SparrowBackend();

    private final TableSessionContext session;
    private final String tableId;
    private final Backend backend;
    private final Map<String, Map<UUID, ActiveProxies>> regions = new LinkedHashMap<>();
    private final AtomicBoolean warningLogged = new AtomicBoolean();
    private int entityCount;

    SparrowRayInteractionProxyCoordinator(TableSessionContext session) {
        this(session, DEFAULT_BACKEND);
    }

    SparrowRayInteractionProxyCoordinator(TableSessionContext session, Backend backend) {
        this.session = session;
        this.tableId = session == null ? null : session.id();
        this.backend = backend;
    }

    synchronized void replace(
        String regionKey,
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactionsByViewer
    ) {
        if (regionKey == null
            || interactionsByViewer == null
            || interactionsByViewer.isEmpty()
            || !this.backend.available()) {
            this.remove(regionKey);
            return;
        }

        Map<UUID, ActiveProxies> previous = this.regions.getOrDefault(regionKey, Map.of());
        if (this.canReuseRegion(previous, interactionsByViewer)) {
            return;
        }
        Map<UUID, ActiveProxies> next = new LinkedHashMap<>();
        List<PendingSpawn> pendingSpawns = new ArrayList<>();
        try {
            for (Map.Entry<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> entry
                : interactionsByViewer.entrySet()) {
                UUID viewerId = entry.getKey();
                List<DisplayInteractionRayRegistry.RayInteraction> interactions = entry.getValue();
                if (viewerId == null || interactions == null || interactions.isEmpty()) {
                    continue;
                }
                ActiveProxies current = previous.get(viewerId);
                if (current != null
                    && current.viewer().isOnline()
                    && this.canReuse(viewerId, current, interactions)) {
                    next.put(viewerId, current);
                    continue;
                }
                Player viewer = this.session.onlinePlayer(viewerId);
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                List<InteractionGeometry> geometry = interactionGeometry(interactions);
                List<ClientProxy> proxies = List.copyOf(this.backend.create(viewer, interactions));
                if (!proxies.isEmpty()) {
                    ActiveProxies created = new ActiveProxies(
                        viewer,
                        proxies,
                        geometry,
                        proxies.get(0).entityId()
                    );
                    next.put(viewerId, created);
                    pendingSpawns.add(new PendingSpawn(viewerId, created));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            this.backend.disable();
            this.remove(regionKey);
            this.warn("Sparrow Heart could not create client-only table input proxies.", exception);
            return;
        }

        previous.forEach((viewerId, active) -> {
            if (next.get(viewerId) != active) {
                this.removeActive(viewerId, active);
            }
        });
        int previousCount = proxyCount(previous);
        if (next.isEmpty()) {
            this.regions.remove(regionKey);
            this.entityCount -= previousCount;
            return;
        }

        Map<UUID, ActiveProxies> immutableNext = Map.copyOf(next);
        this.regions.put(regionKey, immutableNext);
        this.entityCount += proxyCount(immutableNext) - previousCount;
        for (PendingSpawn pending : pendingSpawns) {
            UUID viewerId = pending.viewerId();
            ActiveProxies active = pending.active();
            for (int index = 0; index < active.proxies().size(); index++) {
                ClientInteractionProxyRegistry.register(active.entityId(index), viewerId, this.tableId);
            }
            try {
                this.session.runForViewer(
                    active.viewer(),
                    () -> this.spawnIfCurrent(regionKey, viewerId, active)
                );
            } catch (RuntimeException exception) {
                this.remove(regionKey);
                this.backend.disable();
                this.warn("Client-only table input proxy scheduling failed.", exception);
                return;
            }
        }
    }

    private boolean canReuseRegion(
        Map<UUID, ActiveProxies> previous,
        Map<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> interactionsByViewer
    ) {
        int reusableViewers = 0;
        for (Map.Entry<UUID, List<DisplayInteractionRayRegistry.RayInteraction>> entry
            : interactionsByViewer.entrySet()) {
            UUID viewerId = entry.getKey();
            List<DisplayInteractionRayRegistry.RayInteraction> interactions = entry.getValue();
            if (viewerId == null || interactions == null || interactions.isEmpty()) {
                continue;
            }
            ActiveProxies active = previous.get(viewerId);
            if (active == null
                || !active.viewer().isOnline()
                || !this.canReuse(viewerId, active, interactions)) {
                return false;
            }
            reusableViewers++;
        }
        return reusableViewers == previous.size();
    }

    private boolean canReuse(
        UUID viewerId,
        ActiveProxies active,
        List<DisplayInteractionRayRegistry.RayInteraction> interactions
    ) {
        if (active == null || active.proxies().isEmpty() || !sameGeometry(active.geometry(), interactions)) {
            return false;
        }
        return this.hasOwnership(viewerId, active);
    }

    private boolean hasOwnership(UUID viewerId, ActiveProxies active) {
        for (int index = 0; index < active.proxies().size(); index++) {
            if (!ClientInteractionProxyRegistry.isOwnedBy(active.entityId(index), viewerId, this.tableId)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameGeometry(
        List<InteractionGeometry> geometry,
        List<DisplayInteractionRayRegistry.RayInteraction> interactions
    ) {
        if (geometry.size() != interactions.size()) {
            return false;
        }
        for (int index = 0; index < geometry.size(); index++) {
            if (!geometry.get(index).matches(interactions.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static List<InteractionGeometry> interactionGeometry(
        List<DisplayInteractionRayRegistry.RayInteraction> interactions
    ) {
        List<InteractionGeometry> geometry = new ArrayList<>(interactions.size());
        for (DisplayInteractionRayRegistry.RayInteraction interaction : interactions) {
            geometry.add(new InteractionGeometry(
                interaction.worldId(),
                interaction.centerX(),
                interaction.centerY(),
                interaction.centerZ(),
                interaction.acrossX(),
                interaction.acrossZ(),
                interaction.width(),
                interaction.height(),
                interaction.depth()
            ));
        }
        return List.copyOf(geometry);
    }

    synchronized boolean isCurrent(String regionKey, Set<UUID> expectedOwners) {
        if (!this.backend.available()) {
            return true;
        }
        Map<UUID, ActiveProxies> activeByViewer = this.regions.get(regionKey);
        for (UUID ownerId : expectedOwners) {
            Player viewer = this.session.onlinePlayer(ownerId);
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            ActiveProxies active = activeByViewer == null ? null : activeByViewer.get(ownerId);
            if (active == null || active.proxies().isEmpty() || !this.hasOwnership(ownerId, active)) {
                return false;
            }
        }
        return true;
    }

    synchronized void remove(String regionKey) {
        Map<UUID, ActiveProxies> removed = this.regions.remove(regionKey);
        if (removed == null) {
            return;
        }
        this.entityCount -= proxyCount(removed);
        removed.forEach(this::removeActive);
    }

    synchronized void removeViewer(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        for (String regionKey : List.copyOf(this.regions.keySet())) {
            Map<UUID, ActiveProxies> activeByViewer = this.regions.get(regionKey);
            if (activeByViewer == null || !activeByViewer.containsKey(viewerId)) {
                continue;
            }
            Map<UUID, ActiveProxies> remaining = new LinkedHashMap<>(activeByViewer);
            ActiveProxies removed = remaining.remove(viewerId);
            if (remaining.isEmpty()) {
                this.regions.remove(regionKey);
            } else {
                this.regions.put(regionKey, Map.copyOf(remaining));
            }
            this.entityCount -= removed.proxies().size();
            this.removeActive(viewerId, removed);
        }
    }

    synchronized void clear() {
        for (String regionKey : List.copyOf(this.regions.keySet())) {
            this.remove(regionKey);
        }
        this.entityCount = 0;
        ClientInteractionProxyRegistry.clearTable(this.tableId);
    }

    synchronized void shutdown() {
        Map<String, Map<UUID, ActiveProxies>> activeRegions = Map.copyOf(this.regions);
        this.regions.clear();
        this.entityCount = 0;
        ClientInteractionProxyRegistry.clearTable(this.tableId);
        activeRegions.values().forEach(activeByViewer -> activeByViewer.forEach((viewerId, active) -> {
            if (active.viewer().isOnline()) {
                this.destroyQuietly(active.viewer(), active.proxies());
            }
        }));
    }

    synchronized int entityCount() {
        return this.entityCount;
    }

    private static int proxyCount(Map<UUID, ActiveProxies> activeByViewer) {
        int count = 0;
        for (ActiveProxies active : activeByViewer.values()) {
            count += active.proxies().size();
        }
        return count;
    }

    private void spawnIfCurrent(String regionKey, UUID viewerId, ActiveProxies expected) {
        synchronized (this) {
            Map<UUID, ActiveProxies> activeByViewer = this.regions.get(regionKey);
            if (activeByViewer == null
                || activeByViewer.get(viewerId) != expected
                || !expected.viewer().isOnline()) {
                return;
            }
        }
        try {
            this.backend.spawn(expected.viewer(), expected.proxies());
        } catch (RuntimeException | LinkageError exception) {
            synchronized (this) {
                this.remove(regionKey);
                this.backend.disable();
            }
            this.warn("Sparrow Heart could not spawn client-only table input proxies.", exception);
        }
    }

    private void unregister(UUID viewerId, ActiveProxies active) {
        for (int index = 0; index < active.proxies().size(); index++) {
            ClientInteractionProxyRegistry.unregister(active.entityId(index), viewerId, this.tableId);
        }
    }

    private void removeActive(UUID viewerId, ActiveProxies active) {
        this.unregister(viewerId, active);
        if (!active.viewer().isOnline()) {
            return;
        }
        try {
            this.session.runForViewer(
                active.viewer(),
                () -> this.destroyQuietly(active.viewer(), active.proxies())
            );
        } catch (RuntimeException exception) {
            this.warn("Client-only table input proxy cleanup could not be scheduled.", exception);
        }
    }

    private void destroyQuietly(Player viewer, List<ClientProxy> proxies) {
        try {
            this.backend.destroy(viewer, proxies);
        } catch (RuntimeException | LinkageError exception) {
            this.warn("Sparrow Heart could not remove client-only table input proxies.", exception);
        }
    }

    private void warn(String message, Throwable exception) {
        if (!this.warningLogged.compareAndSet(false, true)) {
            return;
        }
        try {
            this.session.plugin().getLogger().log(Level.WARNING, message, exception);
        } catch (RuntimeException ignored) {
            // Tests and shutdown may not expose a live plugin logger.
        }
    }

    interface Backend {
        boolean available();

        List<ClientProxy> create(
            Player viewer,
            List<DisplayInteractionRayRegistry.RayInteraction> interactions
        );

        void spawn(Player viewer, List<ClientProxy> proxies);

        void destroy(Player viewer, List<ClientProxy> proxies);

        default void disable() {
        }
    }

    interface ClientProxy {
        int entityId();
    }

    private record ActiveProxies(
        Player viewer,
        List<ClientProxy> proxies,
        List<InteractionGeometry> geometry,
        int firstEntityId
    ) {
        private int entityId(int index) {
            return index == 0 ? this.firstEntityId : this.proxies.get(index).entityId();
        }
    }

    private record PendingSpawn(UUID viewerId, ActiveProxies active) {
    }

    private record InteractionGeometry(
        UUID worldId,
        double centerX,
        double centerY,
        double centerZ,
        double acrossX,
        double acrossZ,
        float width,
        float height,
        float depth
    ) {
        private boolean matches(DisplayInteractionRayRegistry.RayInteraction interaction) {
            return Objects.equals(this.worldId, interaction.worldId())
                && Double.compare(this.centerX, interaction.centerX()) == 0
                && Double.compare(this.centerY, interaction.centerY()) == 0
                && Double.compare(this.centerZ, interaction.centerZ()) == 0
                && Double.compare(this.acrossX, interaction.acrossX()) == 0
                && Double.compare(this.acrossZ, interaction.acrossZ()) == 0
                && Float.compare(this.width, interaction.width()) == 0
                && Float.compare(this.height, interaction.height()) == 0
                && Float.compare(this.depth, interaction.depth()) == 0;
        }
    }

    private static final class SparrowBackend implements Backend {
        private volatile SparrowHeart heart;
        private volatile boolean unavailable;

        @Override
        public boolean available() {
            return !this.unavailable;
        }

        @Override
        public List<ClientProxy> create(
            Player viewer,
            List<DisplayInteractionRayRegistry.RayInteraction> interactions
        ) {
            World world = viewer.getWorld();
            List<ClientProxy> proxies = new ArrayList<>();
            for (DisplayInteractionRayRegistry.RayInteraction interaction : interactions) {
                if (!world.getUID().equals(interaction.worldId())) {
                    continue;
                }
                boolean small = interaction.width() <= SMALL_ARMOR_STAND_WIDTH
                    && interaction.height() <= SMALL_ARMOR_STAND_HEIGHT;
                double proxyWidth = small ? SMALL_ARMOR_STAND_WIDTH : ARMOR_STAND_WIDTH;
                double proxyHeight = small ? SMALL_ARMOR_STAND_HEIGHT : ARMOR_STAND_HEIGHT;
                int columns = Math.max(
                    1,
                    Math.min(
                        MAX_COLUMNS_PER_INTERACTION,
                        (int) Math.ceil(interaction.width() / proxyWidth)
                    )
                );
                int rows = Math.max(
                    1,
                    Math.min(
                        MAX_ROWS_PER_INTERACTION,
                        (int) Math.ceil(interaction.height() / proxyHeight)
                    )
                );
                double cellWidth = interaction.width() / columns;
                double cellHeight = interaction.height() / rows;
                for (int row = 0; row < rows; row++) {
                    double sampleY = interaction.centerY()
                        - interaction.height() * 0.5D
                        + cellHeight * (row + 0.5D);
                    for (int column = 0; column < columns; column++) {
                        double acrossOffset = -interaction.width() * 0.5D
                            + cellWidth * (column + 0.5D);
                        Location location = new Location(
                            world,
                            interaction.centerX() + interaction.acrossX() * acrossOffset,
                            sampleY - proxyHeight * 0.5D,
                            interaction.centerZ() + interaction.acrossZ() * acrossOffset
                        );
                        FakeArmorStand armorStand = SparrowFakeEntityFactory.createArmorStand(
                            this.heart(),
                            location
                        );
                        armorStand.small(small);
                        armorStand.invisible(true);
                        proxies.add(new SparrowArmorStandProxy(armorStand));
                    }
                }
            }
            return List.copyOf(proxies);
        }

        @Override
        public void spawn(Player viewer, List<ClientProxy> proxies) {
            for (ClientProxy proxy : proxies) {
                ((SparrowArmorStandProxy) proxy).delegate().spawn(viewer);
            }
        }

        @Override
        public void destroy(Player viewer, List<ClientProxy> proxies) {
            if (proxies.isEmpty()) {
                return;
            }
            int[] entityIds = proxies.stream().mapToInt(ClientProxy::entityId).toArray();
            this.heart().removeClientSideEntity(viewer, entityIds);
        }

        @Override
        public void disable() {
            this.unavailable = true;
        }

        private SparrowHeart heart() {
            SparrowHeart current = this.heart;
            if (current == null) {
                current = SparrowHeart.getInstance();
                if (current == null) {
                    throw new IllegalStateException("Sparrow Heart is unavailable");
                }
                this.heart = current;
            }
            return current;
        }
    }

    private record SparrowArmorStandProxy(FakeArmorStand delegate) implements ClientProxy {
        @Override
        public int entityId() {
            return this.delegate.entityID();
        }
    }
}
