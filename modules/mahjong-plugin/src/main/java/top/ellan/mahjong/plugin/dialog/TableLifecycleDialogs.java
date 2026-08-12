package top.ellan.mahjong.plugin.dialog;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.MahjongRuntime;

/** Confirmation dialogs for lifecycle mutations on one explicitly addressed table. */
final class TableLifecycleDialogs {
    private final MahjongDialogService host;
    private final MahjongRuntime runtime;

    TableLifecycleDialogs(MahjongDialogService host, MahjongRuntime runtime) {
        this.host = host;
        this.runtime = runtime;
    }

    void confirmLiveLeave(Player player, TableId tableId) {
        host.show(player, DialogUi.confirmation(
                host.title(player, "mahjongpaper.dialog.live_leave.title", "Leave this match?"),
                host.textComponent(
                        player,
                        "mahjongpaper.dialog.live_leave.body",
                        "Trustee will finish this match for you; your seat is released when it ends.",
                        NamedTextColor.YELLOW),
                host.button(
                        player,
                        "mahjongpaper.dialog.button.confirm",
                        "Confirm",
                        NamedTextColor.RED,
                        host.callback(ignored -> host.lobbyAction(
                                player, runtime.leave(MahjongDialogService.id(player)), tableId, false))),
                host.button(
                        player,
                        "mahjongpaper.dialog.button.cancel",
                        "Cancel",
                        NamedTextColor.GRAY,
                        host.callback(ignored -> host.openTable(player, Optional.of(tableId))))));
    }

    void confirmRemove(Player player, TableId tableId) {
        host.show(player, DialogUi.confirmation(
                host.title(player, "mahjongpaper.dialog.remove.title", "Remove table?"),
                host.textComponent(
                        player,
                        "mahjongpaper.dialog.remove.body",
                        "This permanently closes table %s and cannot be undone.",
                        NamedTextColor.RED,
                        DialogContent.shortId(tableId)),
                host.button(
                        player,
                        "mahjongpaper.dialog.button.confirm",
                        "Confirm",
                        NamedTextColor.RED,
                        host.callback(ignored -> remove(player, tableId))),
                host.button(
                        player,
                        "mahjongpaper.dialog.button.cancel",
                        "Cancel",
                        NamedTextColor.GRAY,
                        host.callback(ignored -> host.openTable(player, Optional.of(tableId))))));
    }

    private void remove(Player player, TableId tableId) {
        CompletionStage<Void> removal;
        try {
            removal = player.hasPermission("mahjongpaper.admin")
                    ? runtime.remove(tableId)
                    : runtime.removeOwnedLobby(tableId, MahjongDialogService.id(player));
        } catch (RuntimeException rejected) {
            host.failure(player, rejected);
            return;
        }
        removal.whenComplete((ignored, failure) -> {
            if (failure != null) {
                host.failure(player, failure);
                return;
            }
            host.forget(tableId);
            host.tell(
                    player,
                    "mahjongpaper.command.table_removed",
                    "Removed table %s.",
                    NamedTextColor.GREEN,
                    tableId);
        });
    }
}
