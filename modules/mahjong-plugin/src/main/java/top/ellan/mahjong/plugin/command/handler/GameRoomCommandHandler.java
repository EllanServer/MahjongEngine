package top.ellan.mahjong.plugin.command.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.plugin.gameroom.GameRoom;
import top.ellan.mahjong.plugin.gameroom.GameRoomRegistry;
import top.ellan.mahjong.plugin.gameroom.GameRoomRuntime;

/** Bounded game-room administration; list output is paged and no table/player catalog is scanned. */
public final class GameRoomCommandHandler implements SubcommandHandler {
    private static final int PAGE_SIZE = 10;
    private static final List<String> OPERATIONS =
            List.of("create", "delete", "list", "info", "wand");
    private final CommandSupport support;

    public GameRoomCommandHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return Set.of("room", "gameroom");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        support.requireAdmin(sender);
        GameRoomRuntime rooms = support.runtime().gameRooms();
        if (!rooms.enabled()) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.room.disabled", "Game rooms are disabled.");
        }
        if (arguments.length < 2) {
            throw usage();
        }
        switch (arguments[1].toLowerCase(java.util.Locale.ROOT)) {
            case "create" -> create(sender, rooms, arguments);
            case "delete" -> delete(sender, rooms, arguments);
            case "list" -> list(sender, rooms, arguments);
            case "info" -> info(sender, rooms, arguments);
            case "wand" -> wand(sender, rooms, arguments);
            default -> throw usage();
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("mahjongpaper.admin")) {
            return List.of();
        }
        if (arguments.length == 2) {
            return CommandSupport.filter(arguments[1], OPERATIONS);
        }
        if (arguments.length == 3
                && ("delete".equalsIgnoreCase(arguments[1])
                        || "info".equalsIgnoreCase(arguments[1]))) {
            return support.runtime().gameRooms().registry().completeIds(arguments[2]);
        }
        if (arguments.length == 3 && "list".equalsIgnoreCase(arguments[1])) {
            int pageCount =
                    support.runtime().gameRooms().registry().page(1, PAGE_SIZE).pageCount();
            ArrayList<String> pages = new ArrayList<>(Math.min(pageCount, 50));
            for (int page = 1; page <= Math.min(pageCount, 50); page++) {
                pages.add(String.valueOf(page));
            }
            return CommandSupport.filter(arguments[2], pages);
        }
        return List.of();
    }

    private void create(CommandSender sender, GameRoomRuntime rooms, String[] arguments) {
        if (arguments.length < 3) {
            throw CommandSupport.usage("/mahjong room create <id> [name]");
        }
        Player player = support.requirePlayer(sender);
        String name =
                arguments.length == 3
                        ? arguments[2]
                        : String.join(" ", Arrays.copyOfRange(arguments, 3, arguments.length));
        support.complete(
                sender,
                rooms.create(player, arguments[2], name),
                result ->
                        result.created()
                                ? CommandSupport.message(
                                        "mahjongpaper.command.room.created",
                                        "Created game room %s (%s): %s",
                                        result.room().id(),
                                        result.room().name(),
                                        result.room().boundsSummary())
                                : CommandSupport.message(
                                        "mahjongpaper.command.room.exists",
                                        "Game room %s already exists.",
                                        result.room().id()));
    }

    private void delete(CommandSender sender, GameRoomRuntime rooms, String[] arguments) {
        if (arguments.length != 3) {
            throw CommandSupport.usage("/mahjong room delete <id>");
        }
        String id = GameRoom.normalizeId(arguments[2]);
        support.complete(
                sender,
                rooms.delete(id),
                deleted ->
                        deleted
                                ? CommandSupport.message(
                                        "mahjongpaper.command.room.deleted",
                                        "Deleted game room %s.",
                                        id)
                                : CommandSupport.message(
                                        "mahjongpaper.command.room.not_found",
                                        "Game room %s does not exist.",
                                        id));
    }

    private void list(CommandSender sender, GameRoomRuntime rooms, String[] arguments) {
        if (arguments.length > 3) {
            throw CommandSupport.usage("/mahjong room list [page]");
        }
        int requested = arguments.length == 3 ? parsePage(arguments[2]) : 1;
        GameRoomRegistry.RoomPage page = rooms.registry().page(requested, PAGE_SIZE);
        StringBuilder output =
                new StringBuilder(
                        support.text(
                                sender,
                                "mahjongpaper.command.room.list_header",
                                "Game rooms - page %s/%s (%s total)",
                                page.page(),
                                page.pageCount(),
                                page.total()));
        for (GameRoom room : page.rooms()) {
            output.append('\n')
                    .append(
                            support.text(
                                    sender,
                                    "mahjongpaper.command.room.list_entry",
                                    "- %s (%s): %s",
                                    room.id(),
                                    room.name(),
                                    room.boundsSummary()));
        }
        if (page.rooms().isEmpty()) {
            output.append('\n')
                    .append(
                            support.text(
                                    sender,
                                    "mahjongpaper.command.room.list_empty",
                                    "No game rooms have been created."));
        }
        support.reply(
                sender,
                CommandSupport.message(
                        "mahjongpaper.command.room.output", "%s", output.toString()));
    }

    private void info(CommandSender sender, GameRoomRuntime rooms, String[] arguments) {
        if (arguments.length > 3) {
            throw CommandSupport.usage("/mahjong room info [id]");
        }
        GameRoom room;
        if (arguments.length == 3) {
            room = rooms.registry().find(arguments[2]).orElse(null);
        } else {
            room = rooms.roomAt(support.requirePlayer(sender).getLocation()).orElse(null);
        }
        if (room == null) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.room.not_found_here",
                    "No matching game room was found.");
        }
        String owner = room.ownerId() == null ? "-" : room.ownerId().toString();
        String text =
                support.text(
                        sender,
                        "mahjongpaper.command.room.info",
                        "%s (%s)\nBounds: %s\nSize: %sx%sx%s\nOwner: %s",
                        room.id(),
                        room.name(),
                        room.boundsSummary(),
                        room.sizeX(),
                        room.sizeY(),
                        room.sizeZ(),
                        owner);
        support.reply(
                sender,
                CommandSupport.message("mahjongpaper.command.room.output", "%s", text));
    }

    private void wand(CommandSender sender, GameRoomRuntime rooms, String[] arguments) {
        if (arguments.length != 2) {
            throw CommandSupport.usage("/mahjong room wand");
        }
        Player player = support.requirePlayer(sender);
        if (!player.getInventory().addItem(rooms.createWand(player.locale())).isEmpty()) {
            throw CommandSupport.failure(
                    "mahjongpaper.command.room.inventory_full",
                    "Your inventory is full.");
        }
        support.reply(
                sender,
                CommandSupport.message(
                        "mahjongpaper.command.room.wand_given",
                        "Room wand given. Left-click and right-click two corners."));
    }

    private static int parsePage(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw CommandSupport.usage("/mahjong room list [page]");
        }
    }

    private static RuntimeException usage() {
        return CommandSupport.usage(
                "/mahjong room <wand|create|delete|list|info> [...]");
    }
}
