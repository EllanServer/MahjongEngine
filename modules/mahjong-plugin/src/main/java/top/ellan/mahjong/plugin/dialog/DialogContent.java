package top.ellan.mahjong.plugin.dialog;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.SeatId;

/** Localized text and compact state formatting shared by every dialog page. */
final class DialogContent {
    private final MahjongPaperPlugin plugin;
    private final LocalizedMessageCatalog messages;

    DialogContent(MahjongPaperPlugin plugin, LocalizedMessageCatalog messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    Component lobbyBody(Player player, TableLobby state) {
        int ready = (int) state.seats().stream().filter(LobbySeat::ready).count();
        Component body = labeled(player, "mahjongpaper.dialog.table.rule", "Rules",
                state.ruleId() + " / " + state.profileId(), NamedTextColor.WHITE)
                .appendNewline().append(labeled(player, "mahjongpaper.dialog.table.seats", "Seats",
                        state.occupiedSeatCount() + "/" + state.seats().size()
                                + " · " + ready + " ready",
                        NamedTextColor.WHITE))
                .appendNewline().append(labeled(player, "mahjongpaper.dialog.table.spectators",
                        "Spectators", Integer.toString(state.spectators().size()),
                        NamedTextColor.WHITE))
                .appendNewline();
        return body.append(lobbySeatLines(player, state));
    }

    Component lobbySeatLines(Player player, TableLobby state) {
        Component body = Component.empty();
        for (int index = 0; index < state.seats().size(); index++) {
            LobbySeat seat = state.seats().get(index);
            if (index > 0) {
                body = body.appendNewline();
            }
            String occupant = seat.occupant().isEmpty()
                    ? t(player, "mahjongpaper.dialog.seat.empty", "Empty")
                    : state.isBotSeat(seat)
                            ? t(player, "mahjongpaper.dialog.seat.bot", "Bot")
                            : playerName(seat.occupant().orElseThrow());
            String status = seat.occupant().isEmpty() ? "" : " · " + (seat.ready()
                    ? t(player, "mahjongpaper.dialog.seat.ready", "Ready")
                    : t(player, "mahjongpaper.dialog.seat.not_ready", "Not ready"));
            String owner = seat.occupant().filter(state.ownerId()::equals).isPresent()
                    ? " · " + t(player, "mahjongpaper.dialog.seat.owner", "Owner") : "";
            body = body.append(Component.text(seatName(player, seat.seatId()) + " · ",
                            NamedTextColor.GOLD))
                    .append(Component.text(occupant + status + owner,
                            seat.ready() ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        }
        return body;
    }

    Component matchBody(Player player, StartedRulePackMatch match, TableProjection projection) {
        Component body = labeled(player, "mahjongpaper.dialog.table.rule", "Rules",
                match.binding().rulePack() + " / " + match.binding().profile(), NamedTextColor.WHITE);
        String phase = projection == null ? match.actor().snapshot().lifecycle().name()
                : projection.publicView().phase();
        body = body.appendNewline().append(labeled(player,
                "mahjongpaper.dialog.attribute.phase", "Phase", pretty(phase), NamedTextColor.WHITE));
        for (TableParticipant participant : match.participants().stream()
                .sorted(Comparator.comparing(value -> value.seat().map(SeatId::value).orElse(99)))
                .toList()) {
            body = body.appendNewline().append(Component.text(
                    participant.seat().map(seat -> seatName(player, seat)).orElse("•") + " · ",
                    NamedTextColor.GOLD)).append(Component.text(
                    playerName(participant.playerId()) + " · " + pretty(participant.role().name()),
                    participant.role() == ParticipantRole.BOT
                            ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
        }
        return body;
    }

    Component title(Player player, String key, String fallback, Object... arguments) {
        return Component.text(t(player, key, fallback, arguments), NamedTextColor.GOLD);
    }

    Component labeled(
            Player player, String key, String fallback, String value, NamedTextColor valueColor) {
        return Component.text(t(player, key, fallback) + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, valueColor));
    }

    Component text(
            Player player, String key, String fallback, NamedTextColor color, Object... arguments) {
        return Component.text(t(player, key, fallback, arguments), color);
    }

    String t(Player player, String key, String fallback, Object... arguments) {
        return messages.format(player.locale(), key, fallback,
                java.util.Arrays.stream(arguments).map(String::valueOf).toList());
    }

    String playerName(PlayerId playerId) {
        Player online = plugin.getServer().getPlayer(playerId.value());
        return online == null ? playerId.value().toString().substring(0, 8) : online.getName();
    }

    String prettyValue(StartedRulePackMatch match, String value) {
        String rendered = value;
        for (TableParticipant participant : match.participants()) {
            rendered = rendered.replace(participant.playerId().value().toString(),
                    playerName(participant.playerId()));
        }
        return pretty(rendered);
    }

    String seatName(Player player, SeatId seat) {
        String[] keys = {"east", "south", "west", "north"};
        String[] names = {"East", "South", "West", "North"};
        int index = seat.value();
        return index < keys.length ? t(player, "mahjongpaper.tile." + keys[index], names[index])
                : Integer.toString(index + 1);
    }

    static String configurationText(Map<String, String> configuration) {
        return configuration.isEmpty() ? "—" : configuration.toString();
    }

    static String shortId(TableId tableId) {
        return tableId.toString().substring(0, 8);
    }

    static String pretty(String value) {
        return humanize(value).replace('-', ' ');
    }

    static String humanize(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ').replace('.', ' ').toLowerCase(Locale.ROOT);
    }
}
