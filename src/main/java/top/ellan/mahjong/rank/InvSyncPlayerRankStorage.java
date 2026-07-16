package top.ellan.mahjong.rank;

import top.ellan.mahjong.config.PluginSettings;
import top.ellan.mahjong.db.DatabaseService;
import top.ellan.mahjong.db.MahjongSoulRankProfile;
import top.ellan.mahjong.db.MahjongSoulRankRules;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.model.MahjongRule;
import top.ellan.mahjong.runtime.ServerScheduler;
import top.ellan.mahjong.table.core.TableFinalStanding;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** InvSync-backed authoritative player profile store with a local synchronized-online cache. */
public final class InvSyncPlayerRankStorage implements PlayerRankStorage, InvSyncReflectionBridge.EventHandler {
    public static final String DATA_KEY = "mahjongpaper:rank-profile";
    private static final long OFFLINE_CLEANUP_DELAY_TICKS = 72_000L;

    private final Supplier<DatabaseService> database;
    private final Supplier<PluginSettings> settings;
    private final ServerScheduler scheduler;
    private final Logger logger;
    private final PlayerRankPayloadCodec codec = new PlayerRankPayloadCodec();
    private final ConcurrentHashMap<UUID, CachedPlayer> players = new ConcurrentHashMap<>();
    private final Map<String, AppliedMatch> appliedOperations = new ConcurrentHashMap<>();
    private final Object rankUpdateLock = new Object();
    private volatile boolean healthy = true;
    @SuppressWarnings("unused")
    private Object registeredAddon;

    private InvSyncPlayerRankStorage(
        Supplier<DatabaseService> database,
        Supplier<PluginSettings> settings,
        ServerScheduler scheduler,
        Logger logger
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public static InvSyncPlayerRankStorage createAndRegister(
        ClassLoader invSyncClassLoader,
        Supplier<DatabaseService> database,
        Supplier<PluginSettings> settings,
        ServerScheduler scheduler,
        Logger logger
    ) throws ReflectiveOperationException {
        InvSyncPlayerRankStorage storage = new InvSyncPlayerRankStorage(database, settings, scheduler, logger);
        storage.registeredAddon = InvSyncReflectionBridge.register(invSyncClassLoader, storage);
        return storage;
    }

    @Override
    public Backend backend() {
        return this.healthy ? Backend.INVSYNC : Backend.UNAVAILABLE;
    }

    @Override
    public boolean rankingEnabled() {
        PluginSettings current = this.settings.get();
        return this.healthy && current != null && current.rankingEnabled();
    }

    boolean healthy() {
        return this.healthy;
    }

    @Override
    public void onRuntimeFailure(Throwable failure) {
        this.healthy = false;
        this.logger.log(
            Level.SEVERE,
            "InvSync rank callback became API-incompatible at runtime; backend state is now UNAVAILABLE and database failover will be used when configured.",
            failure
        );
    }

    @Override
    public Map<MahjongVariant, MahjongSoulRankProfile> loadProfiles(UUID playerId, String displayName)
        throws PlayerRankStorageException {
        if (!this.healthy) {
            throw new PlayerRankStorageException(
                PlayerRankStorageException.Reason.UNAVAILABLE,
                "InvSync rank callback is unavailable"
            );
        }
        CachedPlayer cached = this.players.get(playerId);
        if (cached == null) {
            throw new PlayerRankStorageException(
                PlayerRankStorageException.Reason.SYNC_PENDING,
                "InvSync has not synchronized this online player yet"
            );
        }
        synchronized (cached) {
            if (!cached.writable) {
                throw new PlayerRankStorageException(cached.failureReason, cached.failureMessage);
            }
            cached.rename(displayName);
            return Map.copyOf(cached.profiles);
        }
    }

    @Override
    public CompletableFuture<Void> persistMatchRanksAsync(
        String operationId,
        String tableId,
        MahjongVariant mode,
        MahjongRule.GameLength length,
        List<TableFinalStanding> standings
    ) {
        if (!this.rankingEnabled() || mode != MahjongVariant.RIICHI) {
            return CompletableFuture.completedFuture(null);
        }
        List<TableFinalStanding> humans = standings.stream().filter(standing -> !standing.bot()).toList();
        if (humans.size() < 4) {
            return CompletableFuture.completedFuture(null);
        }
        String fingerprint = mode.name() + ':' + length.name() + ':' + List.copyOf(humans);
        AppliedMatch applied;
        synchronized (this.rankUpdateLock) {
            applied = this.appliedOperations.get(operationId);
            if (applied != null && !applied.fingerprint.equals(fingerprint)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Rank operation ID was reused for different standings"));
            }
            if (applied == null) {
                try {
                    applied = this.applyMatch(fingerprint, length, humans);
                } catch (PlayerRankStorageException exception) {
                    return CompletableFuture.failedFuture(exception);
                }
                this.appliedOperations.put(operationId, applied);
                if (this.appliedOperations.size() > 8192) {
                    this.appliedOperations.keySet().stream().findFirst().ifPresent(this.appliedOperations::remove);
                }
            }
        }
        DatabaseService projection = this.database.get();
        if (projection == null || !projection.rankingEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return projection.persistRankProjectionAsync(operationId, tableId, mode, applied.projectionEntries);
    }

    private AppliedMatch applyMatch(
        String fingerprint,
        MahjongRule.GameLength gameLength,
        List<TableFinalStanding> standings
    ) throws PlayerRankStorageException {
        PluginSettings currentSettings = this.settings.get();
        MahjongSoulRankRules.MatchLength matchLength = MahjongSoulRankRules.matchLength(gameLength);
        MahjongSoulRankRules.Room room = MahjongSoulRankRules.roomFor(
            matchLength,
            currentSettings.rankingEastRoom(),
            currentSettings.rankingSouthRoom()
        );
        Map<UUID, MahjongSoulRankProfile> currentProfiles = new java.util.LinkedHashMap<>();
        boolean allCelestial = true;
        for (TableFinalStanding standing : standings) {
            CachedPlayer cached = this.players.get(standing.playerId());
            if (cached == null) {
                throw new PlayerRankStorageException(
                    PlayerRankStorageException.Reason.SYNC_PENDING,
                    "InvSync rank state is missing for " + standing.playerId()
                );
            }
            MahjongSoulRankProfile profile;
            synchronized (cached) {
                if (!cached.writable) {
                    throw new PlayerRankStorageException(cached.failureReason, cached.failureMessage);
                }
                cached.rename(standing.displayName());
                profile = cached.profiles.get(MahjongVariant.RIICHI);
            }
            currentProfiles.put(standing.playerId(), profile);
            allCelestial &= profile.isCelestial();
        }
        List<MahjongSoulRankProfile> fieldProfiles = List.copyOf(currentProfiles.values());
        List<DatabaseService.RankProjectionEntry> projectionEntries = new ArrayList<>();
        List<PlayerUpdate> updates = new ArrayList<>();
        for (TableFinalStanding standing : standings) {
            MahjongSoulRankRules.RankedMatchResult result = MahjongSoulRankRules.applyMatch(
                currentProfiles.get(standing.playerId()),
                room,
                matchLength,
                standing.place(),
                standing.points(),
                allCelestial,
                fieldProfiles
            );
            CachedPlayer cached = this.players.get(standing.playerId());
            if (cached == null || !cached.writable) {
                throw new PlayerRankStorageException(
                    PlayerRankStorageException.Reason.SYNC_PENDING,
                    "InvSync rank state changed while applying a match"
                );
            }
            updates.add(new PlayerUpdate(cached, result.updated()));
            projectionEntries.add(new DatabaseService.RankProjectionEntry(standing.displayName(), result));
        }
        for (PlayerUpdate update : updates) {
            CachedPlayer cached = update.cached;
            synchronized (cached) {
                cached.profiles.put(MahjongVariant.RIICHI, update.updated);
                cached.revision++;
            }
        }
        return new AppliedMatch(fingerprint, List.copyOf(projectionEntries));
    }

    @Override
    public void onSync(Object event) throws ReflectiveOperationException {
        Object player = invokeNoArgs(event, "getPlayer");
        UUID playerId = (UUID) invokeNoArgs(player, "getUniqueId");
        String displayName = Objects.toString(invokeNoArgs(player, "getName"), playerId.toString());
        synchronized (this.rankUpdateLock) {
            CachedPlayer existing = this.players.get(playerId);
            if (existing != null) {
                synchronized (existing) {
                    if (existing.writable && (existing.revision > existing.savedRevision || existing.pendingMigrationMarker)) {
                        existing.rename(displayName);
                        this.repairProjection(existing.profiles);
                        return;
                    }
                }
            }
            byte[] payload = (byte[]) invoke(event, "readData", new Class<?>[] { String.class }, DATA_KEY);
            if (payload == null) {
                CachedPlayer migrated = this.migrateLegacyProfile(playerId, displayName);
                this.players.put(playerId, migrated);
                if (migrated.writable) {
                    this.repairProjection(migrated.profiles);
                }
                return;
            }
            try {
                PlayerRankPayloadCodec.Decoded decoded = this.codec.decode(playerId, payload);
                CachedPlayer synchronizedProfile = CachedPlayer.writable(decoded.profiles(), !decoded.migrated());
                this.players.put(playerId, synchronizedProfile);
                this.repairProjection(synchronizedProfile.profiles);
            } catch (IOException exception) {
                this.players.put(playerId, CachedPlayer.failed(
                    PlayerRankStorageException.Reason.CORRUPT_REMOTE_DATA,
                    "InvSync rank payload is corrupt; it was preserved without overwrite"
                ));
                this.logger.log(Level.SEVERE, "InvSync rank payload is corrupt for player=" + playerId + "; refusing to overwrite it.", exception);
            }
        }
    }

    private CachedPlayer migrateLegacyProfile(UUID playerId, String displayName) {
        DatabaseService legacy = this.database.get();
        PluginSettings currentSettings = this.settings.get();
        if (legacy == null && currentSettings != null && currentSettings.database().enabled()) {
            this.logger.severe(
                "Legacy rank migration cannot verify player=" + playerId + " because the configured database failed to start; remote data will not be overwritten."
            );
            return CachedPlayer.failed(
                PlayerRankStorageException.Reason.DATABASE_FAILURE,
                "Legacy rank migration is waiting for the configured database"
            );
        }
        if (legacy != null && legacy.rankingEnabled()) {
            long startedAt = System.nanoTime();
            try {
                Map<MahjongVariant, MahjongSoulRankProfile> imported = legacy.loadRankProfiles(playerId, displayName);
                long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                this.logger.info(
                    "Migrated legacy database rank profile into InvSync cache for player=" + playerId + " in " + elapsedMillis + "ms"
                );
                if (elapsedMillis > 100L) {
                    this.logger.warning(
                        "Legacy InvSync rank migration blocked its one-time sync callback for " + elapsedMillis
                            + "ms; check database latency."
                    );
                }
                return CachedPlayer.writable(imported, true);
            } catch (SQLException exception) {
                this.logger.log(Level.SEVERE, "Could not import legacy rank data for player=" + playerId + "; remote data will not be overwritten.", exception);
                return CachedPlayer.failed(
                    PlayerRankStorageException.Reason.DATABASE_FAILURE,
                    "Legacy rank migration failed; retry after the database recovers"
                );
            }
        }
        Map<MahjongVariant, MahjongSoulRankProfile> defaults = new EnumMap<>(MahjongVariant.class);
        for (MahjongVariant mode : MahjongVariant.values()) {
            defaults.put(mode, MahjongSoulRankProfile.defaultProfile(playerId, displayName));
        }
        return CachedPlayer.writable(defaults, true);
    }

    private void repairProjection(Map<MahjongVariant, MahjongSoulRankProfile> profiles) {
        DatabaseService projection = this.database.get();
        if (projection != null && projection.rankingEnabled()) {
            projection.repairRankProjectionAsync(Map.copyOf(profiles));
        }
    }

    @Override
    public void onSave(Object event, Object reason) throws ReflectiveOperationException {
        Object player = invokeNoArgs(event, "getPlayer");
        UUID playerId = (UUID) invokeNoArgs(player, "getUniqueId");
        CachedPlayer cached = this.players.get(playerId);
        if (cached == null) {
            return;
        }
        byte[] payload;
        long savedRevision;
        synchronized (this.rankUpdateLock) {
            synchronized (cached) {
                if (!cached.writable) {
                    return;
                }
                payload = this.codec.encode(playerId, cached.profiles, true);
                savedRevision = cached.revision;
            }
        }
        invoke(event, "putData", new Class<?>[] { String.class, byte[].class }, DATA_KEY, payload);
        synchronized (cached) {
            cached.savedRevision = Math.max(cached.savedRevision, savedRevision);
            cached.pendingMigrationMarker = false;
        }
        if (isTerminalSave(reason)) {
            this.scheduler.runGlobalDelayed(() -> {
                synchronized (cached) {
                    if (cached.revision == cached.savedRevision) {
                        this.players.remove(playerId, cached);
                    }
                }
            }, OFFLINE_CLEANUP_DELAY_TICKS);
        }
    }

    int cachedPlayerCount() {
        return this.players.size();
    }

    private static boolean isTerminalSave(Object reason) {
        if (reason == null) {
            return false;
        }
        String value = reason.toString().toUpperCase(Locale.ROOT);
        return value.contains("QUIT") || value.contains("KICK") || value.contains("DISCONNECT") || value.contains("LOGOUT");
    }

    private static Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments)
        throws ReflectiveOperationException {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            throw new ReflectiveOperationException("InvSync event method " + methodName + " failed", cause);
        }
    }

    private static final class CachedPlayer {
        private final EnumMap<MahjongVariant, MahjongSoulRankProfile> profiles;
        private final boolean writable;
        private final PlayerRankStorageException.Reason failureReason;
        private final String failureMessage;
        private long revision;
        private long savedRevision;
        private boolean pendingMigrationMarker;

        private CachedPlayer(
            Map<MahjongVariant, MahjongSoulRankProfile> profiles,
            boolean writable,
            PlayerRankStorageException.Reason failureReason,
            String failureMessage,
            boolean pendingMigrationMarker
        ) {
            this.profiles = new EnumMap<>(MahjongVariant.class);
            this.profiles.putAll(profiles);
            this.writable = writable;
            this.failureReason = failureReason;
            this.failureMessage = failureMessage;
            this.pendingMigrationMarker = pendingMigrationMarker;
        }

        private static CachedPlayer writable(
            Map<MahjongVariant, MahjongSoulRankProfile> profiles,
            boolean pendingMigrationMarker
        ) {
            return new CachedPlayer(profiles, true, null, "", pendingMigrationMarker);
        }

        private static CachedPlayer failed(PlayerRankStorageException.Reason reason, String message) {
            return new CachedPlayer(Map.of(), false, reason, message, false);
        }

        private void rename(String displayName) {
            if (displayName == null || displayName.isBlank()) {
                return;
            }
            this.profiles.replaceAll((mode, profile) -> new MahjongSoulRankProfile(
                profile.playerId(),
                displayName,
                profile.tier(),
                profile.level(),
                profile.rankPoints(),
                profile.totalMatches(),
                profile.firstPlaces(),
                profile.secondPlaces(),
                profile.thirdPlaces(),
                profile.fourthPlaces()
            ));
        }
    }

    private record AppliedMatch(String fingerprint, List<DatabaseService.RankProjectionEntry> projectionEntries) {
    }

    private record PlayerUpdate(CachedPlayer cached, MahjongSoulRankProfile updated) {
    }
}
