package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongVariant;

import top.ellan.mahjong.compat.CraftEngineService;
import top.ellan.mahjong.compat.PaperCompatibility;
import top.ellan.mahjong.model.SeatWind;
import top.ellan.mahjong.render.display.DisplayClickAction;
import top.ellan.mahjong.render.display.DisplayClickAction.ActionType;
import top.ellan.mahjong.render.display.DisplayEntities;
import top.ellan.mahjong.riichi.ReactionResponses;
import top.ellan.mahjong.table.runtime.BotActionScheduler;
import top.ellan.mahjong.table.runtime.ChunkNeighborhood;
import top.ellan.mahjong.table.runtime.TableRefreshCoordinator;
import top.ellan.mahjong.table.runtime.TableOverheadViewCoordinator;
import top.ellan.mahjong.table.runtime.TableSeatCoordinator;
import top.ellan.mahjong.ui.RuleSettingsUi;
import top.ellan.mahjong.ui.SettlementUi;
import top.ellan.mahjong.ui.TableControlUi;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.EventExecutor;
import top.ellan.mahjong.runtime.PluginTask;

public final class MahjongTableManager implements Listener {
    private static final String ADMIN_PERMISSION = "mahjongpaper.admin";
    private static final long DUPLICATE_DISPLAY_ACTION_WINDOW_NANOS = 40_000_000L;
    private static final long DUPLICATE_HAND_TILE_CLICK_WINDOW_NANOS = 40_000_000L;
    private static final long RECENT_DISPLAY_ACTION_TTL_SECONDS = 60L;
    private static final long RECENT_HAND_TILE_CLICK_TTL_SECONDS = 60L;
    private static final double PERSISTED_TABLE_CLEANUP_RADIUS_XZ = 4.5D;
    private static final double PERSISTED_TABLE_CLEANUP_RADIUS_Y = 3.5D;
    private static final double ADMIN_NEAREST_TABLE_RADIUS = 4.5D;
    private static final int PERSISTED_TABLE_CLEANUP_REMOVALS_PER_TICK = 8;
    private final TableRuntimeServices plugin;
    private final PersistentTableStore persistentTableStore;
    private final TableDirectory directory = new TableDirectory();
    private final TablePlacementService placementService;
    // Caffeine cache with 1-minute TTL bounds memory growth even if a PlayerQuitEvent
    // is missed; entries also expire naturally once the duplicate-click window passes.
    private final Cache<UUID, RecentHandTileClick> recentHandTileClicks = Caffeine.newBuilder()
        .expireAfterWrite(RECENT_HAND_TILE_CLICK_TTL_SECONDS, TimeUnit.SECONDS)
        .build();
    private final Cache<UUID, RecentDisplayAction> recentDisplayActions = Caffeine.newBuilder()
        .expireAfterWrite(RECENT_DISPLAY_ACTION_TTL_SECONDS, TimeUnit.SECONDS)
        .build();
    private final TableSeatCoordinator seatCoordinator;
    private final TableOverheadViewCoordinator overheadViewCoordinator;
    private final TableRefreshCoordinator refreshCoordinator;
    private final TableEventCoordinator eventCoordinator;
    private final TableMembershipCoordinator membershipCoordinator;
    private final PluginTask tableTickTask;

    public MahjongTableManager(TableRuntimeServices plugin) {
        this.plugin = plugin;
        this.persistentTableStore = new PersistentTableStore(
            plugin::database,
            plugin.async(),
            plugin.bukkitPlugin().getLogger(),
            plugin.settings().tablePersistenceEnabled()
        );
        this.placementService = new TablePlacementService(plugin, this.directory);
        this.seatCoordinator = new TableSeatCoordinator(plugin::craftEngine, plugin.scheduler(), this);
        this.overheadViewCoordinator = new TableOverheadViewCoordinator(plugin);
        this.refreshCoordinator = new TableRefreshCoordinator(
            plugin.debug(),
            plugin.scheduler(),
            this,
            this.seatCoordinator,
            plugin.settings().tableStartupRebuildBatchSize()
        );
        this.eventCoordinator = new TableEventCoordinator(this);
        this.membershipCoordinator = new TableMembershipCoordinator(this);
        this.registerSeatVehicleEvents();
        this.tableTickTask = plugin.scheduler().runGlobalTimer(this::dispatchTableTicks, 20L, 20L);
    }

    public Listener eventListener() {
        return this.eventCoordinator;
    }

    private void registerSeatVehicleEvents() {
        this.registerSeatVehicleEvent("org.bukkit.event.entity.EntityMountEvent", EventPriority.NORMAL, this.eventCoordinator::onSeatMount);
        this.registerSeatVehicleEvent("org.spigotmc.event.entity.EntityMountEvent", EventPriority.NORMAL, this.eventCoordinator::onSeatMount);
        this.registerSeatVehicleEvent("org.bukkit.event.entity.EntityDismountEvent", EventPriority.HIGHEST, this.eventCoordinator::onSeatDismount);
        this.registerSeatVehicleEvent("org.spigotmc.event.entity.EntityDismountEvent", EventPriority.HIGHEST, this.eventCoordinator::onSeatDismount);
    }

    private void registerSeatVehicleEvent(String className, EventPriority priority, Consumer<Event> handler) {
        try {
            Class<?> rawEventClass = Class.forName(className);
            if (!Event.class.isAssignableFrom(rawEventClass)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEventClass;
            EventExecutor executor = (listener, event) -> handler.accept(event);
            this.plugin.bukkitPlugin().getServer().getPluginManager().registerEvent(eventClass, this.eventCoordinator, priority, executor, this.plugin.bukkitPlugin(), true);
        } catch (ClassNotFoundException exception) {
            // The mount/dismount event package changed across Paper versions; the other package may be present.
        }
    }

    public CreateTableResult createTable(Player owner) {
        if (this.isViewingAnyTable(owner.getUniqueId())) {
            return CreateTableResult.created(this.sessionForViewer(owner.getUniqueId()));
        }
        String id = this.nextId();
        Location center = this.normalizedTableCenter(owner.getLocation());
        CreateTableFailure failure = this.placementService.validateCreation(owner, center);
        if (failure != null) {
            this.plugin.debug().log("table", "Rejected table create for " + owner.getName() + ": " + failure.reason());
            return CreateTableResult.rejected(failure);
        }
        MahjongTableSession session = new MahjongTableSession(this.plugin, id, center, true);
        session.setOwner(owner.getUniqueId());
        this.registerTable(session);
        session.render();
        this.persistTables();
        this.plugin.debug().log("table", "Created table " + id + " for " + owner.getName());
        return CreateTableResult.created(session);
    }

    public MahjongTableSession createBotMatch(Player owner, String preset) {
        if (this.isViewingAnyTable(owner.getUniqueId())) {
            return null;
        }

        // Resolve the preset first so we know the correct variant and rule set.
        SessionRulePresetResolver.Preset resolved = (preset != null && !preset.isBlank())
            ? SessionRulePresetResolver.resolve(preset)
            : null;
        MahjongVariant variant = resolved != null ? resolved.variant() : MahjongVariant.RIICHI;
        top.ellan.mahjong.riichi.model.MahjongRule rule = resolved != null
            ? resolved.rule()
            : SessionRulePresetResolver.majsoulRule(top.ellan.mahjong.riichi.model.MahjongRule.GameLength.TWO_WIND);

        String id = this.nextId();
        Location center = this.normalizedTableCenter(owner.getLocation());

        CreateTableFailure placementFailure = this.placementService.validateCreation(owner, center);
        if (placementFailure != null) {
            this.placementService.sendCreationFailure(owner, placementFailure);
            return null;
        }

        MahjongTableSession session = new MahjongTableSession(
            this.plugin,
            id,
            center,
            variant,
            rule,
            true,
            true
        );
        session.addPlayer(owner);
        this.registerTable(session);
        this.directory.assignPlayer(owner.getUniqueId(), id);

        while (session.size() < 4) {
            session.addBot();
        }
        session.removePlayer(owner.getUniqueId());
        this.directory.removePlayer(owner.getUniqueId());
        while (session.size() < 4) {
            session.addBot();
        }
        session.addSpectator(owner);
        this.directory.assignSpectator(owner.getUniqueId(), id);
        session.startRound();
        this.persistTables();
        this.plugin.debug().log("table", "Created 4-bot match " + id + " (variant=" + variant + ") for spectator " + owner.getName());
        return session;
    }

    public MahjongTableSession join(Player player, String tableId) {
        return this.membershipCoordinator.join(player, tableId);
    }

    public MahjongTableSession join(Player player, String tableId, SeatWind wind) {
        return this.membershipCoordinator.join(player, tableId, wind);
    }

    public MahjongTableSession spectate(Player player, String tableId) {
        return this.membershipCoordinator.spectate(player, tableId);
    }

    public LeaveResult leave(UUID playerId) {
        return this.membershipCoordinator.leave(playerId);
    }

    public MahjongTableSession unspectate(UUID playerId) {
        return this.membershipCoordinator.unspectate(playerId);
    }

    public MahjongTableSession tableFor(UUID playerId) {
        return this.directory.tableFor(playerId);
    }

    public MahjongTableSession sessionForViewer(UUID playerId) {
        return this.directory.sessionForViewer(playerId);
    }

    TableRuntimeServices pluginRef() {
        return this.plugin;
    }

    TableSeatCoordinator seatCoordinatorRef() {
        return this.seatCoordinator;
    }

    TableOverheadViewCoordinator overheadViewCoordinatorRef() {
        return this.overheadViewCoordinator;
    }

    public TableOverheadViews overheadViews() {
        return this.overheadViewCoordinator;
    }

    TableRefreshCoordinator refreshCoordinatorRef() {
        return this.refreshCoordinator;
    }

    TableDirectory directoryRef() {
        return this.directory;
    }

    void clearRecentHandInput(UUID playerId) {
        this.recentHandTileClicks.invalidate(playerId);
        this.recentDisplayActions.invalidate(playerId);
    }

    boolean isViewingAnyTableInternal(UUID playerId) {
        return this.isViewingAnyTable(playerId);
    }

    public boolean isSpectating(UUID playerId) {
        return this.directory.isSpectating(playerId);
    }

    public Collection<MahjongTableSession> tables() {
        return this.directory.tables();
    }

    public Collection<String> tableIds() {
        return this.directory.tableIds();
    }

    public MahjongTableSession nearestTable(Location location) {
        return this.nearestTable(location, ADMIN_NEAREST_TABLE_RADIUS);
    }

    public MahjongTableSession nearestTable(Location location, double maxDistance) {
        if (location == null || location.getWorld() == null || maxDistance < 0.0D) {
            return null;
        }
        double maxDistanceSquared = maxDistance * maxDistance;
        MahjongTableSession nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (MahjongTableSession table : this.directory.tables()) {
            Location center = table.center();
            if (center.getWorld() == null || !center.getWorld().equals(location.getWorld())) {
                continue;
            }
            double distanceSquared = center.distanceSquared(location);
            if (distanceSquared > maxDistanceSquared || distanceSquared >= nearestDistanceSquared) {
                continue;
            }
            nearest = table;
            nearestDistanceSquared = distanceSquared;
        }
        return nearest;
    }

    public void loadPersistentTables() {
        for (PersistentTableStore.LoadedTable loadedTable : this.persistentTableStore.load()) {
            String id = loadedTable.id().toUpperCase(Locale.ROOT);
            if (this.directory.containsTableId(id)) {
                this.plugin.bukkitPlugin().getLogger().warning("Persistent table id " + id + " already exists in memory, deleting it before startup rebuild.");
                this.deleteTable(id);
            }
            this.createPersistentTable(loadedTable.id(), loadedTable.center(), loadedTable.ownerId(), loadedTable.variant(), loadedTable.rule(), loadedTable.botMatch());
            this.refreshCoordinator.enqueueStartupRefresh(id);
            this.plugin.debug().log("table", "Queued persistent table " + id + " for startup rebuild");
        }
    }

    public void persistTables() {
        this.persistentTableStore.save(this.directory.tables());
    }

    public void refreshPersistentTablesAfterStartup() {
        for (MahjongTableSession session : this.directory.tables()) {
            if (session.isPersistentRoom()) {
                this.refreshCoordinator.enqueueStartupRefresh(session.id());
            }
        }
        this.refreshCoordinator.scheduleStartupRefreshTask();
    }

    public MahjongTableSession.ReadyResult start(Player player) {
        MahjongTableSession session = this.tableFor(player.getUniqueId());
        if (session == null) {
            throw new IllegalStateException("Player is not in a table");
        }
        MahjongTableSession.ReadyResult result = session.toggleReady(player.getUniqueId());
        this.plugin.debug().log("table", player.getName() + " toggled ready on table " + session.id() + " -> " + result.name());
        return result;
    }

    public MahjongTableSession forceEndTable(String tableId) {
        MahjongTableSession session = this.resolveTable(tableId);
        if (session == null) {
            return null;
        }
        this.overheadViewCoordinator.closeTable(session.id());
        this.plugin.debug().log("table", "Force-ended table " + session.id());
        session.forceEndMatch();
        return session;
    }

    public void removePlayerFromTableWithoutMove(UUID playerId) {
        this.membershipCoordinator.removePlayerFromTableWithoutMove(playerId);
    }

    public MahjongTableSession deleteTable(String tableId) {
        MahjongTableSession session = this.resolveTable(tableId);
        if (session == null) {
            return null;
        }
        Location center = session.center();

        this.overheadViewCoordinator.closeTable(session.id());

        for (UUID playerId : session.players()) {
            SeatWind wind = session.seatOf(playerId);
            this.directory.removePlayer(playerId);
            this.seatCoordinator.movePlayerToSeatExit(playerId, session, wind);
        }
        for (UUID spectatorId : session.spectators()) {
            this.directory.removeSpectator(spectatorId);
        }
        this.refreshCoordinator.clearPendingArtifactCleanup(session.id());
        session.shutdown();
        this.cleanupTableArtifactsAt(center);
        this.directory.removeTable(session);
        this.persistTables();
        this.plugin.debug().log("table", "Deleted table " + session.id());
        return session;
    }

    public boolean clickTile(Player player, String tableId, UUID ownerId, int tileIndex) {
        MahjongTableSession session = this.directory.resolveTable(tableId);
        if (session == null || !session.contains(player.getUniqueId()) || !player.getUniqueId().equals(ownerId)) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (!session.isSichuanExchangePhase(ownerId) && this.isDuplicateHandTileClick(playerId, tableId, ownerId, tileIndex)) {
            this.plugin.debug().log("table", player.getName() + " duplicate hand tile click ignored for tile index " + tileIndex + " on table " + tableId);
            return true;
        }
        this.plugin.debug().log("table", player.getName() + " clicked tile index " + tileIndex + " on table " + tableId);
        boolean accepted = session.clickHandTile(ownerId, tileIndex, player.isSneaking());
        if (accepted) {
            this.rememberHandTileClick(playerId, tableId, ownerId, tileIndex);
        }
        return accepted;
    }

    public boolean handleDisplayAction(Player player, DisplayClickAction action) {
        if (player == null || action == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (playerId != null) {
            if (this.isDuplicateDisplayAction(playerId, action)) {
                return true;
            }
            this.recentDisplayActions.put(playerId, new RecentDisplayAction(action, System.nanoTime()));
        }
        if (action.actionType() == ActionType.HAND_TILE) {
            return this.clickTile(player, action.tableId(), action.ownerId(), action.tileIndex());
        }
        if (action.actionType() == ActionType.JOIN_SEAT) {
            MahjongTableSession currentSeat = this.tableFor(player.getUniqueId());
            if (isSameSeatJoin(currentSeat, player.getUniqueId(), action)) {
                this.seatCoordinator.requestSeatRestore(player, currentSeat, action.seatWind());
                return true;
            }
            MahjongTableSession session = this.join(player, action.tableId(), action.seatWind());
            if (session == null) {
                return false;
            }
            this.plugin.messages().send(player, "command.joined_table", this.plugin.messages().tag("table_id", session.id()));
            this.seatCoordinator.requestSeatRestore(player, session, action.seatWind());
            return true;
        }
        if (action.actionType() == ActionType.TOGGLE_READY) {
            MahjongTableSession session = this.directory.resolveTable(action.tableId());
            if (session == null || session.seatOf(player.getUniqueId()) != action.seatWind()) {
                return false;
            }
            this.sendReadyResult(player, session.toggleReady(player.getUniqueId()));
            return true;
        }
        if (action.actionType() == ActionType.PLAYER_COMMAND) {
            return this.handlePlayerCommandAction(player, action);
        }
        return false;
    }

    private boolean handlePlayerCommandAction(Player player, DisplayClickAction action) {
        if (player == null || action == null || action.ownerId() == null || action.command() == null || action.command().isBlank()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (!playerId.equals(action.ownerId())) {
            return false;
        }
        MahjongTableSession session = this.directory.resolveTable(action.tableId());
        if (session == null || !session.contains(playerId)) {
            return false;
        }
        String[] parts = action.command().split(":");
        if (parts.length < 2) {
            return false;
        }
        String category = parts[0].toLowerCase(Locale.ROOT);
        String operation = parts[1].toLowerCase(Locale.ROOT);
        if ("react".equals(category)) {
            boolean accepted = this.handleReactionCommand(session, playerId, operation, parts);
            if (accepted) {
                session.clearViewerActionMenuState(playerId);
                session.flushViewerActionsNow(playerId);
            }
            return accepted;
        }
        if ("turn".equals(category)) {
            boolean accepted = this.handleTurnCommand(session, playerId, operation, parts);
            if (accepted) {
                session.clearViewerActionMenuState(playerId);
                session.flushViewerActionsNow(playerId);
            }
            return accepted;
        }
        if ("menu".equals(category)) {
            return this.handleMenuCommand(session, playerId, operation);
        }
        if ("lobby".equals(category)) {
            return this.handleLobbyCommand(player, session, playerId, operation, parts);
        }
        if ("view".equals(category)) {
            return this.handleViewCommand(player, session, playerId, operation);
        }
        return false;
    }

    private boolean handleViewCommand(Player player, MahjongTableSession session, UUID playerId, String operation) {
        if (!"river".equals(operation)) {
            return false;
        }
        SeatWind wind = session.seatOf(playerId);
        if (wind == null) {
            return false;
        }
        TableOverheadViewCoordinator.ToggleResult result = this.overheadViewCoordinator.toggle(player, session, wind);
        if (result == TableOverheadViewCoordinator.ToggleResult.REJECTED) {
            session.flushViewerActionsNow(playerId);
        }
        return true;
    }

    private boolean handleReactionCommand(MahjongTableSession session, UUID playerId, String operation, String[] parts) {
        return switch (operation) {
            case "ron" -> session.react(playerId, ReactionResponses.RON);
            case "pon" -> session.react(playerId, ReactionResponses.PON);
            case "minkan" -> session.react(playerId, ReactionResponses.MINKAN);
            case "skip" -> session.react(playerId, ReactionResponses.SKIP);
            case "chii" -> {
                if (parts.length < 4) {
                    yield false;
                }
                top.ellan.mahjong.riichi.model.MahjongTile first = parseRiichiTile(parts[2]);
                top.ellan.mahjong.riichi.model.MahjongTile second = parseRiichiTile(parts[3]);
                if (first == null || second == null) {
                    yield false;
                }
                yield session.react(playerId, ReactionResponses.chii(first, second));
            }
            default -> false;
        };
    }

    public boolean canManageTable(Player player, MahjongTableSession session) {
        return player != null
            && session != null
            && (player.hasPermission(ADMIN_PERMISSION) || session.isOwner(player.getUniqueId()));
    }

    public boolean canDeleteTable(Player player, MahjongTableSession session) {
        return this.canManageTable(player, session) && (player.hasPermission(ADMIN_PERMISSION) || !session.isStarted());
    }

    public boolean canBreakTable(Player player, MahjongTableSession session) {
        return player != null
            && session != null
            && this.placementService.canBreak(player, session.center());
    }

    private boolean handleTurnCommand(MahjongTableSession session, UUID playerId, String operation, String[] parts) {
        return switch (operation) {
            case "tsumo" -> session.declareTsumo(playerId);
            case "kyuushu" -> session.declareKyuushuKyuuhai(playerId);
            case "kan" -> parts.length < 3 ? false : session.declareKan(playerId, parts[2].toLowerCase(Locale.ROOT));
            case "flower" -> {
                if (parts.length < 3) {
                    yield false;
                }
                try {
                    yield session.declareFlower(playerId, Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                    yield false;
                }
            }
            case "dingque" -> parts.length < 3 ? false : session.chooseSichuanMissingSuit(playerId, parts[2]);
            case "riichi" -> {
                if (parts.length < 3) {
                    yield false;
                }
                try {
                    yield session.declareRiichi(playerId, Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                    yield false;
                }
            }
            default -> false;
        };
    }

    private boolean handleMenuCommand(MahjongTableSession session, UUID playerId, String operation) {
        if ("back".equals(operation)) {
            session.clearViewerActionMenuState(playerId);
            session.flushViewerActionsNow(playerId);
            return true;
        }
        if (!"react-chii".equals(operation)
            && !"turn-kan".equals(operation)
            && !"turn-riichi".equals(operation)
            && !"turn-flower".equals(operation)) {
            return false;
        }
        session.setViewerActionMenuState(playerId, operation);
        session.flushViewerActionsNow(playerId);
        return true;
    }

    private boolean handleLobbyCommand(
        Player player,
        MahjongTableSession session,
        UUID playerId,
        String operation,
        String[] parts
    ) {
        return switch (operation) {
            case "join" -> {
                if (parts.length < 3) {
                    yield false;
                }
                SeatWind wind;
                try {
                    wind = SeatWind.valueOf(parts[2].trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    yield false;
                }
                MahjongTableSession joined = this.join(player, session.id(), wind);
                if (joined == null) {
                    yield false;
                }
                this.plugin.messages().send(player, "command.joined_table", this.plugin.messages().tag("table_id", joined.id()));
                this.seatCoordinator.requestSeatRestore(player, joined, wind);
                yield true;
            }
            case "ready" -> {
                if (session.seatOf(playerId) == null) {
                    yield false;
                }
                this.sendReadyResult(player, session.toggleReady(playerId));
                yield true;
            }
            case "leave" -> {
                LeaveResult leaveResult = this.leave(playerId);
                this.sendLeaveResult(player, leaveResult);
                yield leaveResult.status() != LeaveStatus.NOT_IN_TABLE && leaveResult.status() != LeaveStatus.BLOCKED;
            }
            case "start" -> {
                if (session.seatOf(playerId) == null || !session.isReady(playerId) || session.isStarted() || session.isRoundStartInProgress()) {
                    yield false;
                }
                if (session.size() < 4 || session.readyCount() < 4) {
                    yield false;
                }
                session.startRound();
                yield true;
            }
            default -> false;
        };
    }

    private static top.ellan.mahjong.riichi.model.MahjongTile parseRiichiTile(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return top.ellan.mahjong.riichi.model.MahjongTile.valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public void shutdown() {
        this.tableTickTask.cancel();
        this.overheadViewCoordinator.shutdown();
        this.seatCoordinator.shutdown();
        this.refreshCoordinator.shutdown();
        this.persistentTableStore.flush(this.directory.tables());
        this.directory.tables().forEach(MahjongTableSession::shutdown);
        this.directory.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        this.refreshCoordinator.handleChunkLoad(event.getChunk(), this.directory.tableIdsNearChunk(event.getChunk()));
    }

    @EventHandler
    public void onSettlementClick(InventoryClickEvent event) {
        if (TableControlUi.isTableControlInventory(PaperCompatibility.getTopInventory(event))) {
            event.setCancelled(true);
            TableControlUi.handleClick(event, this);
            return;
        }
        if (RuleSettingsUi.isRuleInventory(PaperCompatibility.getTopInventory(event))) {
            event.setCancelled(true);
            RuleSettingsUi.handleClick(event, this);
            return;
        }
        if (SettlementUi.isSettlementInventory(PaperCompatibility.getTopInventory(event))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSettlementDrag(InventoryDragEvent event) {
        if (TableControlUi.isTableControlInventory(PaperCompatibility.getTopInventory(event))) {
            event.setCancelled(true);
            return;
        }
        if (RuleSettingsUi.isRuleInventory(PaperCompatibility.getTopInventory(event))) {
            event.setCancelled(true);
            return;
        }
        if (SettlementUi.isSettlementInventory(PaperCompatibility.getTopInventory(event))) {
            event.setCancelled(true);
        }
    }

    private boolean isDuplicateHandTileClick(UUID playerId, String tableId, UUID ownerId, int tileIndex) {
        RecentHandTileClick recent = this.recentHandTileClicks.getIfPresent(playerId);
        if (recent == null || !recent.matches(tableId, ownerId, tileIndex)) {
            return false;
        }
        return System.nanoTime() - recent.timestampNanos() <= DUPLICATE_HAND_TILE_CLICK_WINDOW_NANOS;
    }

    private boolean isDuplicateDisplayAction(UUID playerId, DisplayClickAction action) {
        RecentDisplayAction recent = this.recentDisplayActions.getIfPresent(playerId);
        if (recent == null || !recent.matches(action)) {
            return false;
        }
        return System.nanoTime() - recent.timestampNanos() <= DUPLICATE_DISPLAY_ACTION_WINDOW_NANOS;
    }

    private void rememberHandTileClick(UUID playerId, String tableId, UUID ownerId, int tileIndex) {
        this.recentHandTileClicks.put(playerId, new RecentHandTileClick(tableId, ownerId, tileIndex, System.nanoTime()));
    }

    private String nextId() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String id;
        do {
            StringBuilder builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            id = builder.toString();
        } while (this.directory.containsTableId(id));
        return id;
    }

    private void cleanupTableArtifacts(Location center) {
        World world = center == null ? null : center.getWorld();
        if (world == null) {
            return;
        }
        int removed = 0;
        int scheduledFallbackRemovals = 0;
        CraftEngineService craftEngine = this.plugin.craftEngine();
        for (Entity entity : world.getNearbyEntities(center, PERSISTED_TABLE_CLEANUP_RADIUS_XZ, PERSISTED_TABLE_CLEANUP_RADIUS_Y, PERSISTED_TABLE_CLEANUP_RADIUS_XZ)) {
            boolean managedDisplay = DisplayEntities.isManagedEntity(this.plugin.bukkitPlugin(), entity);
            boolean managedCraftEngineFurniture = craftEngine != null && craftEngine.isManagedFurnitureEntity(entity);
            boolean mahjongCraftEngineFurniture = craftEngine != null && craftEngine.isMahjongFurnitureEntity(entity);
            boolean craftEngineCleanupCandidate = managedCraftEngineFurniture || mahjongCraftEngineFurniture;
            boolean removedByCraftEngine = craftEngineCleanupCandidate && craftEngine.removeFurniture(entity);
            if (!removedByCraftEngine && !managedDisplay && !craftEngineCleanupCandidate) {
                continue;
            }
            if (!removedByCraftEngine && entity.isValid()) {
                if (entity instanceof Interaction interaction) {
                    interaction.setResponsive(false);
                }
                long delayTicks = 1L + (scheduledFallbackRemovals / PERSISTED_TABLE_CLEANUP_REMOVALS_PER_TICK);
                this.plugin.scheduler().removeEntity(entity, delayTicks);
                scheduledFallbackRemovals++;
            }
            removed++;
        }
        if (removed > 0) {
            this.plugin.debug().log("table", "Cleaned " + removed + " table entities near " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ());
        }
    }

    public void cleanupTableArtifactsNow(Location center) {
        this.cleanupTableArtifacts(center);
    }

    public void cleanupTableArtifactsAt(Location center) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        this.plugin.scheduler().runRegion(center, () -> this.cleanupTableArtifacts(center));
    }

    private void dispatchTableTicks() {
        // Each session.tick() runs on its own region. Wrap the per-session
        // dispatch so a single misbehaving table cannot stop ticks for every
        // other table on the server.
        for (MahjongTableSession session : this.directory.tables()) {
            try {
                this.plugin.scheduler().runRegion(session.center(), () -> this.tickSession(session));
            } catch (RuntimeException dispatchException) {
                org.bukkit.Bukkit.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Failed to schedule tick for table " + session.id(),
                    dispatchException
                );
            }
        }
    }

    private void tickSession(MahjongTableSession session) {
        try {
            session.tick();
        } catch (RuntimeException tickException) {
            org.bukkit.Bukkit.getLogger().log(
                java.util.logging.Level.WARNING,
                "Tick failed for table " + session.id(),
                tickException
            );
        }
        // Bot watchdog: if the session is started but has no armed bot task,
        // the bot scheduler may have stalled (e.g. after an unhandled
        // exception in a previous callback). Re-schedule to recover.
        // This runs on the region thread alongside tick() so that game state
        // (engine, currentPlayer, etc.) is accessed from the owning thread.
        if (session.isStarted() && !session.hasArmedBotTask()) {
            try {
                BotActionScheduler.schedule(session);
            } catch (RuntimeException watchdogException) {
                org.bukkit.Bukkit.getLogger().log(
                    java.util.logging.Level.WARNING,
                    "Bot watchdog failed for table " + session.id(),
                    watchdogException
                );
            }
        }
    }

    private record RecentHandTileClick(String tableId, UUID ownerId, int tileIndex, long timestampNanos) {
        private boolean matches(String tableId, UUID ownerId, int tileIndex) {
            return this.tileIndex == tileIndex
                && this.tableId.equals(tableId)
                && this.ownerId.equals(ownerId);
        }
    }

    private record RecentDisplayAction(DisplayClickAction action, long timestampNanos) {
        private boolean matches(DisplayClickAction candidate) {
            return sameDisplayAction(this.action, candidate);
        }
    }

    public boolean canUseSeat(Player player, String tableId, SeatWind wind) {
        if (player == null || tableId == null || wind == null) {
            return false;
        }
        MahjongTableSession session = this.resolveTable(tableId);
        if (session == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        MahjongTableSession currentSeat = this.tableFor(playerId);
        if (currentSeat != null) {
            return currentSeat == session && currentSeat.seatOf(playerId) == wind;
        }
        MahjongTableSession currentView = this.sessionForViewer(playerId);
        if (currentView != null && currentView != session) {
            return false;
        }
        UUID seatedPlayer = session.playerAt(wind);
        return seatedPlayer == null
            || (!session.isStarted() && !session.isRoundStartInProgress() && session.isBot(seatedPlayer));
    }

    public void startSeatWatchdog(MahjongTableSession session, long durationTicks) {
        this.seatCoordinator.startSeatWatchdog(session, durationTicks);
    }

    public void startSeatWatchdog(MahjongTableSession session, UUID playerId, SeatWind wind, long durationTicks) {
        this.seatCoordinator.startSeatWatchdog(session, playerId, wind, durationTicks);
    }

    private MahjongTableSession resolveTable(String tableId) {
        return this.directory.resolveTable(tableId);
    }

    public MahjongTableSession resolveTableById(String tableId) {
        return this.resolveTable(tableId);
    }

    private boolean isViewingAnyTable(UUID playerId) {
        return this.directory.isViewingAnyTable(playerId);
    }

    private MahjongTableSession createPersistentTable(
        String tableId,
        Location center,
        UUID ownerId,
        MahjongVariant variant,
        top.ellan.mahjong.riichi.model.MahjongRule rule,
        boolean botMatchRoom
    ) {
        String normalizedId = tableId.toUpperCase(Locale.ROOT);
        Location normalizedCenter = this.normalizedTableCenter(center);
        MahjongTableSession session = new MahjongTableSession(this.plugin, normalizedId, normalizedCenter, variant, rule, true, botMatchRoom, ownerId);
        this.registerTable(session);
        // Bots are NOT added here. During server startup, adding bots would
        // trigger render() and maybeStartRoundIfReady() before the startup
        // cleanup has a chance to clear leftover entities from the previous
        // session. Bots are added after the startup/chunk refresh completes
        // cleanup+render, via TableRefreshCoordinator.maybeAddBotsForBotMatchRoom().
        this.refreshCoordinator.markPendingArtifactCleanup(normalizedId);
        return session;
    }

    private void registerTable(MahjongTableSession session) {
        this.directory.registerTable(session);
    }

    public void finalizeDeferredLeaves(MahjongTableSession session, Map<UUID, SeatWind> playerSeats) {
        this.membershipCoordinator.finalizeDeferredLeaves(session, playerSeats);
    }

    static CreateTableFailure firstBlockedTableSpace(Location center) {
        return TablePlacementService.firstBlockedTableSpace(center);
    }

    static boolean isOverlappingTableCenter(Location left, Location right) {
        return TablePlacementService.isOverlappingTableCenter(left, right);
    }

    static List<Location> tableFootprint(Location center) {
        return TablePlacementService.tableFootprint(center);
    }

    static boolean isSameSeatJoin(MahjongTableSession currentSeat, UUID playerId, DisplayClickAction action) {
        return currentSeat != null
            && playerId != null
            && action != null
            && action.actionType() == ActionType.JOIN_SEAT
            && currentSeat.id().equals(action.tableId())
            && currentSeat.seatOf(playerId) == action.seatWind();
    }

    static boolean sameDisplayAction(DisplayClickAction left, DisplayClickAction right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.actionType() == right.actionType()
            && java.util.Objects.equals(left.tableId(), right.tableId())
            && java.util.Objects.equals(left.ownerId(), right.ownerId())
            && left.tileIndex() == right.tileIndex()
            && left.seatWind() == right.seatWind()
            && java.util.Objects.equals(left.command(), right.command());
    }

    private Location normalizedTableCenter(Location source) {
        if (source == null || source.getWorld() == null) {
            return source;
        }
        return source.toCenterLocation();
    }

    public void sendReadyResult(Player player, MahjongTableSession.ReadyResult result) {
        if (player == null || result == null) {
            return;
        }
        switch (result) {
            case READY -> this.plugin.messages().send(player, "command.ready_waiting");
            case UNREADY -> this.plugin.messages().send(player, "command.ready_cancelled");
            case STARTED -> this.plugin.messages().send(player, "command.round_started");
            case BLOCKED -> this.plugin.messages().send(player, "command.ready_blocked");
        }
    }

    public void sendLeaveResult(Player player, LeaveResult result) {
        if (player == null || result == null) {
            return;
        }
        switch (result.status()) {
            case LEFT -> this.plugin.messages().send(player, "command.left_table");
            case DEFERRED -> this.plugin.messages().send(player, "command.leave_deferred");
            case UNSPECTATED -> this.plugin.messages().send(player, "command.unspectated");
            case NOT_IN_TABLE -> this.plugin.messages().send(player, "command.not_in_table");
            case BLOCKED -> this.plugin.messages().send(player, "command.leave_blocked_started");
        }
    }

    public record CreateTableResult(MahjongTableSession table, CreateTableFailure failure) {
        private static CreateTableResult created(MahjongTableSession table) {
            return new CreateTableResult(table, null);
        }

        private static CreateTableResult rejected(CreateTableFailure failure) {
            return new CreateTableResult(null, failure);
        }

        public boolean succeeded() {
            return this.table != null && this.failure == null;
        }
    }

    public record CreateTableFailure(CreateTableFailureReason reason, String tableId, Integer x, Integer y, Integer z) {
        private static CreateTableFailure invalidLocation() {
            return new CreateTableFailure(CreateTableFailureReason.INVALID_LOCATION, null, null, null, null);
        }

        private static CreateTableFailure tooCloseToTable(String tableId) {
            return new CreateTableFailure(CreateTableFailureReason.TOO_CLOSE_TO_TABLE, tableId, null, null, null);
        }

        private static CreateTableFailure blockedSpace(int x, int y, int z) {
            return new CreateTableFailure(CreateTableFailureReason.BLOCKED_SPACE, null, x, y, z);
        }

        private static CreateTableFailure notEnoughHeight() {
            return new CreateTableFailure(CreateTableFailureReason.NOT_ENOUGH_HEIGHT, null, null, null, null);
        }

        private static CreateTableFailure notInGameRoom() {
            return new CreateTableFailure(CreateTableFailureReason.NOT_IN_GAME_ROOM, null, null, null, null);
        }

        private static CreateTableFailure protectedArea() {
            return new CreateTableFailure(CreateTableFailureReason.PROTECTED_AREA, null, null, null, null);
        }
    }

    public enum CreateTableFailureReason {
        INVALID_LOCATION,
        TOO_CLOSE_TO_TABLE,
        BLOCKED_SPACE,
        NOT_ENOUGH_HEIGHT,
        NOT_IN_GAME_ROOM,
        PROTECTED_AREA
    }

    public record LeaveResult(LeaveStatus status, MahjongTableSession session) {
    }

    public enum LeaveStatus {
        LEFT,
        DEFERRED,
        UNSPECTATED,
        NOT_IN_TABLE,
        BLOCKED
    }

}
