package top.ellan.mahjong.table.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import top.ellan.mahjong.compat.SparrowFakeEntityFactory;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.table.core.TableSessionContext;

/** Owns the client-only spectator labels for one table. */
final class SparrowViewerOverlayCoordinator {
    private static final Backend DEFAULT_BACKEND = new SparrowBackend();

    private final TableSessionContext session;
    private final Backend backend;
    private final ConcurrentMap<String, ActiveOverlay> overlays = new ConcurrentHashMap<>();
    private final AtomicBoolean fallbackWarningLogged = new AtomicBoolean();

    SparrowViewerOverlayCoordinator(TableSessionContext session) {
        this(session, DEFAULT_BACKEND);
    }

    SparrowViewerOverlayCoordinator(TableSessionContext session, Backend backend) {
        this.session = session;
        this.backend = backend;
    }

    boolean isCurrent(String regionKey, UUID viewerId, long fingerprint) {
        ActiveOverlay overlay = this.overlays.get(regionKey);
        return overlay != null && overlay.viewerId().equals(viewerId) && overlay.fingerprint() == fingerprint;
    }

    boolean canAttempt() {
        return this.backend.available();
    }

    boolean tryUpdate(
        String regionKey,
        UUID viewerId,
        long fingerprint,
        List<DisplayEntities.EntitySpec> specs,
        Runnable spawnFailureFallback
    ) {
        if (regionKey == null || viewerId == null || !isEligible(viewerId, specs)) {
            return false;
        }
        if (this.isCurrent(regionKey, viewerId, fingerprint)) {
            return true;
        }
        if (!this.backend.available()) {
            return false;
        }
        Player viewer = this.session.onlinePlayer(viewerId);
        if (viewer == null || !viewer.isOnline()) {
            this.remove(regionKey);
            return false;
        }

        List<ClientTextDisplay> displays = new ArrayList<>(specs.size());
        try {
            for (DisplayEntities.EntitySpec spec : specs) {
                displays.add(this.backend.create((DisplayEntities.LabelSpec) spec));
            }
        } catch (RuntimeException | LinkageError exception) {
            this.backend.disable();
            this.warnFallback("Sparrow Heart could not create a spectator overlay; using Bukkit TextDisplay fallback.", exception);
            return false;
        }

        ActiveOverlay next = new ActiveOverlay(viewerId, fingerprint, List.copyOf(displays));
        ActiveOverlay previous = this.overlays.put(regionKey, next);
        try {
            this.session.runForViewer(viewer, () -> this.spawn(regionKey, viewer, previous, next, spawnFailureFallback));
        } catch (RuntimeException exception) {
            this.overlays.remove(regionKey, next);
            if (previous != null) {
                this.overlays.putIfAbsent(regionKey, previous);
            }
            this.backend.disable();
            this.warnFallback("Sparrow Heart spectator overlay scheduling failed; using Bukkit TextDisplay fallback.", exception);
            return false;
        }
        return true;
    }

    Set<String> regionKeys() {
        return Set.copyOf(this.overlays.keySet());
    }

    boolean hasRegion(String regionKey) {
        return this.overlays.containsKey(regionKey);
    }

    int regionCount() {
        return this.overlays.size();
    }

    int entityCount() {
        int count = 0;
        for (ActiveOverlay overlay : this.overlays.values()) {
            count += overlay.displays().size();
        }
        return count;
    }

    boolean hasRegions() {
        return !this.overlays.isEmpty();
    }

    boolean hasStaleRegions() {
        for (ActiveOverlay overlay : this.overlays.values()) {
            Player viewer = this.session.onlinePlayer(overlay.viewerId());
            if (viewer == null || !viewer.isOnline()) {
                return true;
            }
        }
        return false;
    }

    void remove(String regionKey) {
        ActiveOverlay overlay = this.overlays.remove(regionKey);
        if (overlay == null) {
            return;
        }
        Player viewer = this.session.onlinePlayer(overlay.viewerId());
        if (viewer == null) {
            return;
        }
        try {
            this.session.runForViewer(viewer, () -> this.destroyQuietly(viewer, overlay.displays()));
        } catch (RuntimeException exception) {
            this.warnFallback("Sparrow Heart spectator overlay cleanup could not be scheduled.", exception);
        }
    }

    void clear() {
        for (String regionKey : new LinkedHashSet<>(this.overlays.keySet())) {
            this.remove(regionKey);
        }
    }

    /** Plugin disable cannot rely on a scheduler task still being accepted. */
    void shutdown() {
        Collection<ActiveOverlay> active = List.copyOf(this.overlays.values());
        this.overlays.clear();
        for (ActiveOverlay overlay : active) {
            Player viewer = this.session.onlinePlayer(overlay.viewerId());
            if (viewer != null) {
                this.destroyQuietly(viewer, overlay.displays());
            }
        }
    }

    private void spawn(
        String regionKey,
        Player viewer,
        ActiveOverlay previous,
        ActiveOverlay next,
        Runnable spawnFailureFallback
    ) {
        if (previous != null) {
            this.destroyQuietly(viewer, previous.displays());
        }
        if (this.overlays.get(regionKey) != next || !viewer.isOnline()) {
            return;
        }
        try {
            this.backend.spawn(viewer, next.displays());
        } catch (RuntimeException | LinkageError exception) {
            this.overlays.remove(regionKey, next);
            this.destroyQuietly(viewer, next.displays());
            this.backend.disable();
            this.warnFallback("Sparrow Heart could not spawn a spectator overlay; using Bukkit TextDisplay fallback.", exception);
            if (viewer.isOnline() && spawnFailureFallback != null) {
                spawnFailureFallback.run();
            }
        }
    }

    private void destroyQuietly(Player viewer, List<ClientTextDisplay> displays) {
        try {
            this.backend.destroy(viewer, displays);
        } catch (RuntimeException | LinkageError exception) {
            this.warnFallback("Sparrow Heart could not remove a spectator overlay.", exception);
        }
    }

    private void warnFallback(String message, Throwable exception) {
        if (!this.fallbackWarningLogged.compareAndSet(false, true)) {
            return;
        }
        try {
            this.session.plugin().getLogger().log(Level.WARNING, message, exception);
        } catch (RuntimeException ignored) {
            // Tests and early shutdown may not expose a live plugin logger.
        }
    }

    private static boolean isEligible(UUID viewerId, List<DisplayEntities.EntitySpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return false;
        }
        for (DisplayEntities.EntitySpec spec : specs) {
            if (!(spec instanceof DisplayEntities.LabelSpec label)
                || label.billboard() != Display.Billboard.CENTER
                || !isPrivateTo(label.privateViewers(), viewerId)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrivateTo(Collection<UUID> privateViewers, UUID viewerId) {
        return privateViewers != null && privateViewers.size() == 1 && privateViewers.contains(viewerId);
    }

    interface Backend {
        boolean available();

        ClientTextDisplay create(DisplayEntities.LabelSpec spec);

        void spawn(Player viewer, List<ClientTextDisplay> displays);

        void destroy(Player viewer, List<ClientTextDisplay> displays);

        default void disable() {
        }
    }

    interface ClientTextDisplay {
        int entityId();
    }

    private record ActiveOverlay(UUID viewerId, long fingerprint, List<ClientTextDisplay> displays) {
    }

    private static final class SparrowBackend implements Backend {
        private volatile SparrowHeart heart;
        private volatile GsonComponentSerializer componentSerializer;
        private volatile boolean unavailable;

        @Override
        public boolean available() {
            return !this.unavailable;
        }

        @Override
        public ClientTextDisplay create(DisplayEntities.LabelSpec spec) {
            FakeTextDisplay display;
            if (this.unavailable) {
                throw new IllegalStateException("Sparrow Heart fake text displays are unavailable");
            }
            display = SparrowFakeEntityFactory.createTextDisplay(this.heart(), spec.location().clone());
            Component text = spec.text() == null ? Component.empty() : spec.text();
            display.name(this.componentSerializer().serialize(text));
            Color color = spec.color();
            if (color != null) {
                display.rgba(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
            }
            return new SparrowTextDisplay(display);
        }

        @Override
        public void spawn(Player viewer, List<ClientTextDisplay> displays) {
            for (ClientTextDisplay display : displays) {
                ((SparrowTextDisplay) display).delegate().spawn(viewer);
            }
        }

        @Override
        public void destroy(Player viewer, List<ClientTextDisplay> displays) {
            if (displays.isEmpty()) {
                return;
            }
            int[] entityIds = displays.stream().mapToInt(ClientTextDisplay::entityId).toArray();
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
                this.heart = current;
            }
            return current;
        }

        private GsonComponentSerializer componentSerializer() {
            GsonComponentSerializer current = this.componentSerializer;
            if (current == null) {
                current = GsonComponentSerializer.gson();
                this.componentSerializer = current;
            }
            return current;
        }
    }

    private record SparrowTextDisplay(FakeTextDisplay delegate) implements ClientTextDisplay {
        @Override
        public int entityId() {
            return this.delegate.entityID();
        }
    }
}
