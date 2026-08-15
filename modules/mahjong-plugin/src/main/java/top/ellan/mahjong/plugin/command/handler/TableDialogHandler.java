package top.ellan.mahjong.plugin.command.handler;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Command fallback for the same native dialogs opened by clicking a physical table. */
public final class TableDialogHandler implements SubcommandHandler {
    private static final List<String> SEATS = List.of("east", "south", "west", "north");

    private final CommandSupport support;

    public TableDialogHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("table", "gui", "panel", "control", "settlement");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        Player player = support.requirePlayer(sender);
        if ("table".equalsIgnoreCase(arguments[0])
                && arguments.length >= 2
                && "owner".equalsIgnoreCase(arguments[1])) {
            transferOwner(sender, player, arguments);
            return;
        }
        if (arguments.length > 2) {
            throw CommandSupport.usage("/mahjong " + arguments[0] + " [table-id]");
        }
        Optional<TableId> tableId = arguments.length == 2
                ? Optional.of(TableId.parse(arguments[1])) : Optional.empty();
        if ("settlement".equalsIgnoreCase(arguments[0])) {
            support.runtime().dialogs().openSettlement(player, tableId);
        } else {
            support.runtime().dialogs().openTable(player, tableId);
        }
    }

    /** v1.5 form: /mahjong table owner <seat|player> [table-id]. */
    private void transferOwner(CommandSender sender, Player player, String[] arguments) {
        if (arguments.length < 3 || arguments.length > 4) {
            throw CommandSupport.usage("/mahjong table owner <seat|player> [table-id]");
        }
        PlayerId actor = new PlayerId(player.getUniqueId());
        HostedLobby lobby;
        if (arguments.length == 4) {
            TableId tableId = TableId.parse(arguments[3]);
            lobby = support.runtime()
                    .lobbyTables()
                    .find(tableId)
                    .orElseThrow(() -> CommandSupport.failure(
                            "mahjongpaper.command.unknown_lobby",
                            "Unknown waiting lobby %s.",
                            tableId));
        } else {
            lobby = support.runtime()
                    .lobbyTables()
                    .findByPlayer(actor)
                    .orElseThrow(() -> CommandSupport.failure(
                            "mahjongpaper.command.not_at_lobby",
                            "You do not belong to a waiting lobby."));
        }
        SeatId target = resolveTarget(lobby, arguments[2]);
        support.complete(
                sender,
                support.runtime().lobbyUseCases().transferOwner(actor, target),
                result -> CommandSupport.message(
                        "mahjongpaper.command.action_result",
                        "%s - revision %s - %s",
                        result.code(),
                        result.revision(),
                        result.reasonCode()));
    }

    private static SeatId resolveTarget(HostedLobby lobby, String value) {
        SeatId explicit = seat(value);
        if (explicit != null) {
            return explicit;
        }
        Player target = Bukkit.getPlayerExact(value);
        if (target == null) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.table_owner_target_not_found",
                    "Player %s is not online.",
                    value);
        }
        PlayerId targetId = new PlayerId(target.getUniqueId());
        return lobby.state()
                .seatOf(targetId)
                .orElseThrow(() -> CommandSupport.failure(
                        "mahjongpaper.command.table_owner_target_not_seated",
                        "%s is not seated at table %s.",
                        target.getName(),
                        lobby.tableId()));
    }

    private static SeatId seat(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "east", "0" -> new SeatId(0);
            case "south", "1" -> new SeatId(1);
            case "west", "2" -> new SeatId(2);
            case "north", "3" -> new SeatId(3);
            default -> null;
        };
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if ("table".equalsIgnoreCase(arguments[0]) && arguments.length == 2) {
            List<String> values = new java.util.ArrayList<>(support.currentTableIds(sender));
            values.add("owner");
            values.addAll(SEATS);
            return CommandSupport.filter(arguments[1], values);
        }
        if ("table".equalsIgnoreCase(arguments[0])
                && arguments.length == 3
                && "owner".equalsIgnoreCase(arguments[1])) {
            return CommandSupport.filter(arguments[2], SEATS);
        }
        if (arguments.length != 2) {
            return List.of();
        }
        return CommandSupport.filter(arguments[1], support.currentTableIds(sender));
    }
}
