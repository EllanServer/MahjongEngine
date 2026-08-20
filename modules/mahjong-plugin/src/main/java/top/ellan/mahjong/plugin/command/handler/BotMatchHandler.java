package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.usecase.CreateLobbyRequest;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.plugin.placement.TablePlacementFailure;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Four-bot demo match aligned with the 1.5.0 contract; the creator is attached as its spectator. */
public final class BotMatchHandler implements SubcommandHandler {
    private static final List<String> PRESETS =
            List.of(
                    "MAJSOUL_HANCHAN",
                    "MAJSOUL_TONPUU",
                    "GB",
                    "SICHUAN",
                    "riichi",
                    "mcr",
                    "sichuan");

    private final CommandSupport support;

    public BotMatchHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("botmatch");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        support.requireAdmin(sender);
        Player owner = support.requirePlayer(sender);
        if (arguments.length > 2) {
            throw CommandSupport.usage("/mahjong botmatch [MAJSOUL_HANCHAN|MAJSOUL_TONPUU|GB|SICHUAN]");
        }
        PlayerId ownerId = new PlayerId(owner.getUniqueId());
        if (support.runtime().lobbyTables().findByPlayer(ownerId).isPresent()
                || support.runtime().liveTables().findByPlayer(ownerId).isPresent()) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.botmatch_failed_in_table",
                    "You already belong to a table; leave or spectate elsewhere first.");
        }
        RuleId ruleId = botMatchRule(arguments.length == 2 ? arguments[1] : null);
        ProfileId profileId = CommandSupport.defaultProfile(ruleId);
        Location source = owner.getLocation();
        Location location =
                new Location(
                        java.util.Objects.requireNonNull(source.getWorld(), "player world"),
                        Math.floor(source.getX()) + 0.5D,
                        Math.floor(source.getY()) + 0.5D,
                        Math.floor(source.getZ()) + 0.5D,
                        0.0F,
                        0.0F);
        support.runtime()
                .placement()
                .validateCreation(owner, location)
                .ifPresent(failure -> {
                    throw placementFailure(failure);
                });
        TableId tableId = TableId.random();
        TableAnchor anchor =
                new TableAnchor(
                        tableId,
                        location.getWorld().getUID().toString(),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch());
        CreateLobbyRequest request =
                new CreateLobbyRequest(anchor, ownerId, ruleId, profileId, Map.of(), 4);
        support.reply(
                sender,
                CommandSupport.message(
                        "mahjongpaper.command.creating_botmatch",
                        "Creating four-bot %s match %s...",
                        ruleId,
                        tableId));
        support.complete(
                sender,
                support.runtime()
                        .createLobby(request, location)
                        .thenCompose(
                                lobby -> {
                                    CompletionStage<TableActionResult> setup =
                                            addBot(lobby.tableId(), ownerId, 0);
                                    for (int seat = 1; seat < 4; seat++) {
                                        final int seatIndex = seat;
                                        setup = setup.thenCompose(
                                                ignored ->
                                                        addBot(
                                                                lobby.tableId(),
                                                                ownerId,
                                                                seatIndex));
                                    }
                                    setup = setup.thenCompose(
                                            ignored ->
                                                    requireAccepted(
                                                            support.runtime()
                                                                    .lobbyUseCases()
                                                                    .spectate(
                                                                            lobby.tableId(),
                                                                            ownerId),
                                                            lobby.tableId(),
                                                            "spectate"));
                                    return setup.thenCompose(
                                            ignored ->
                                                    requireAccepted(
                                                            support.runtime()
                                                                    .lobbyUseCases()
                                                                    .start(ownerId),
                                                            lobby.tableId(),
                                                            "start"));
                                })
                        .thenApply(
                                ignored ->
                                        new BotMatchStart(tableId, ruleId, profileId)),
                started -> CommandSupport.message(
                        "mahjongpaper.command.botmatch_created",
                        "Created four-bot %s/%s match %s; spectating now.",
                        started.ruleId(),
                        started.profileId(),
                        started.tableId()));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("mahjongpaper.admin")) {
            return List.of();
        }
        if (arguments.length == 2) {
            return CommandSupport.filter(arguments[1], PRESETS);
        }
        return List.of();
    }

    private CompletionStage<TableActionResult> addBot(
            TableId tableId, PlayerId ownerId, int seatIndex) {
        return requireAccepted(
                support.runtime()
                        .lobbyUseCases()
                        .addBot(ownerId, new SeatId(seatIndex)),
                tableId,
                "bot-seat-" + seatIndex);
    }

    private static CompletionStage<TableActionResult> requireAccepted(
            CompletionStage<TableActionResult> stage, TableId tableId, String operation) {
        return stage.thenCompose(
                result ->
                        result.code() == TableActionCode.ACCEPTED_MEMORY
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(
                                        new IllegalStateException(
                                                "Bot match setup rejected "
                                                        + tableId
                                                        + ":"
                                                        + operation
                                                        + " ("
                                                        + result.reasonCode()
                                                        + ")")));
    }

    private static RuleId botMatchRule(String rawPreset) {
        if (rawPreset == null || rawPreset.isBlank()) {
            return CommandSupport.ruleId("MAJSOUL_HANCHAN");
        }
        RuleId resolved = CommandSupport.ruleId(rawPreset);
        return switch (resolved.value().toLowerCase(Locale.ROOT)) {
            case "riichi", "mcr", "sichuan" -> resolved;
            default -> CommandSupport.ruleId("MAJSOUL_HANCHAN");
        };
    }

    private static RuntimeException placementFailure(TablePlacementFailure failure) {
        return switch (failure.reason()) {
            case INVALID_LOCATION ->
                    CommandSupport.failure(
                            "mahjongpaper.command.create_failed_invalid_location",
                            "A table cannot be placed at this location.");
            case TOO_CLOSE_TO_TABLE ->
                    CommandSupport.failure(
                            "mahjongpaper.command.create_failed_too_close",
                            "This location is too close to table %s.",
                            failure.conflictingTable().orElseThrow());
            case BLOCKED_SPACE ->
                    CommandSupport.failure(
                            "mahjongpaper.command.create_failed_blocked",
                            "Table space is blocked at %s, %s, %s.",
                            failure.x(),
                            failure.y(),
                            failure.z());
            case NOT_ENOUGH_HEIGHT ->
                    CommandSupport.failure(
                            "mahjongpaper.command.create_failed_height",
                            "There is not enough vertical space for a table here.");
            case NOT_IN_GAME_ROOM ->
                    CommandSupport.failure(
                            "mahjongpaper.command.create_failed_not_in_room",
                            "New tables can only be placed inside a game room.");
            case PROTECTED_AREA ->
                    CommandSupport.failure(
                            "mahjongpaper.command.create_failed_protected",
                            "Land protection does not allow a table at this location.");
        };
    }

    private record BotMatchStart(TableId tableId, RuleId ruleId, ProfileId profileId) {}
}
