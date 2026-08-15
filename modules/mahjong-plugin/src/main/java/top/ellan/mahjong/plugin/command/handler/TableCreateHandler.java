package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.usecase.CreateLobbyRequest;
import top.ellan.mahjong.domain.table.TableAnchor;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.plugin.placement.TablePlacementFailure;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;

/** Creates an empty reusable four-seat table; players join through CE chairs or join command. */
public final class TableCreateHandler implements SubcommandHandler {
    private static final List<String> RULES = List.of("riichi", "mcr", "sichuan");
    private final CommandSupport support;

    public TableCreateHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("create");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (arguments.length < 1 || arguments.length > 3) {
            throw CommandSupport.usage(
                    "/mahjong create [riichi|mcr|sichuan] [profile]");
        }
        Player owner = support.requirePlayer(sender);
        PlayerId ownerId = new PlayerId(owner.getUniqueId());
        var existingLobby = support.runtime().lobbyTables().findByPlayer(ownerId).orElse(null);
        if (existingLobby != null) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.already_at_lobby",
                    "You already belong to lobby %s. If it is your retained empty table, an admin must run /mahjong remove %s before you create another.",
                    existingLobby.tableId(),
                    existingLobby.tableId());
        }
        var existingMatch = support.runtime().liveTables().findByPlayer(ownerId).orElse(null);
        if (existingMatch != null) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.already_at_match",
                    "You already belong to active match %s; finish or leave it before creating another table.",
                    existingMatch.tableId());
        }
        RuleId ruleId =
                arguments.length >= 2
                        ? CommandSupport.ruleId(arguments[1])
                        : CommandSupport.ruleId("riichi");
        ProfileId profileId =
                arguments.length == 3
                        ? new ProfileId(arguments[2].toLowerCase(Locale.ROOT))
                        : CommandSupport.defaultProfile(ruleId);
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
                new CreateLobbyRequest(
                        anchor,
                        ownerId,
                        ruleId,
                        profileId,
                        Map.of(),
                        4);
        support.reply(
                sender,
                CommandSupport.message(
                        "mahjongpaper.command.creating_lobby",
                        "Creating lobby %s asynchronously...",
                        tableId));
        support.complete(
                sender,
                support.runtime().createLobby(request, location),
                hosted -> CommandSupport.message(
                        "mahjongpaper.command.lobby_created",
                        "Created %s with %s/%s; click a CraftEngine chair to sit.",
                        hosted.tableId(),
                        hosted.state().ruleId(),
                        hosted.state().profileId()));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (arguments.length == 1
                || (arguments.length == 2 && arguments[1].isEmpty())) {
            return List.copyOf(RULES);
        }
        if (arguments.length == 2) {
            return CommandSupport.filter(arguments[1], RULES);
        }
        if (arguments.length == 3) {
            return CommandSupport.filter(
                    arguments[2], support.profileIds(arguments[1]));
        }
        return List.of();
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
}
