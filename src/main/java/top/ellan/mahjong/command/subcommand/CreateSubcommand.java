package top.ellan.mahjong.command.subcommand;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.command.MahjongCommandContext;
import top.ellan.mahjong.command.MahjongSubcommand;
import top.ellan.mahjong.table.core.MahjongTableManager.CreateTableFailure;
import top.ellan.mahjong.table.core.MahjongTableManager.CreateTableResult;
import top.ellan.mahjong.table.core.MahjongTableSession;

public final class CreateSubcommand extends AbstractMahjongSubcommand {
    public CreateSubcommand(MahjongCommandContext context) { super(context); }
    public MahjongSubcommand create() { return this.subcommand("create", true); }
    @Override protected void execute(CommandSender sender, Player player, String[] args) {
        CreateTableResult result = this.context.tableManager().createTable(player);
        if (!result.succeeded()) {
            this.sendCreateFailure(player, result.failure());
            return;
        }
        MahjongTableSession table = result.table();
        this.context.messages().send(player, "command.created_table", this.context.messages().tag("table_id", table.id()));
        this.context.messages().send(player, "command.created_table_hint");
    }

    private void sendCreateFailure(Player player, CreateTableFailure failure) {
        if (failure == null) {
            this.context.messages().send(player, "command.create_failed_invalid_location");
            return;
        }
        switch (failure.reason()) {
            case INVALID_LOCATION -> this.context.messages().send(player, "command.create_failed_invalid_location");
            case TOO_CLOSE_TO_TABLE -> this.context.messages().send(
                player,
                "command.create_failed_too_close",
                this.context.messages().tag("table_id", failure.tableId())
            );
            case BLOCKED_SPACE -> this.context.messages().send(
                player,
                "command.create_failed_blocked",
                this.context.messages().tag("x", String.valueOf(failure.x())),
                this.context.messages().tag("y", String.valueOf(failure.y())),
                this.context.messages().tag("z", String.valueOf(failure.z()))
            );
            case NOT_ENOUGH_HEIGHT -> this.context.messages().send(player, "command.create_failed_height");
            case NOT_IN_GAME_ROOM -> this.context.messages().send(player, "command.create_failed_not_in_room");
            case PROTECTED_AREA -> this.context.messages().send(player, "command.create_failed_protected");
        }
    }
}
