package top.ellan.mahjong.plugin.dialog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
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
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.SeatId;

/** Localized text and compact state formatting shared by every dialog page. */
final class DialogContent {
    private static final Pattern VALUE_KEY_UNSAFE = Pattern.compile("[^a-z0-9_.-]");
    private final MahjongPaperPlugin plugin;
    private final LocalizedMessageCatalog messages;

    DialogContent(MahjongPaperPlugin plugin, LocalizedMessageCatalog messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    Component lobbyBody(Player player, TableLobby state) {
        int ready = (int) state.seats().stream().filter(LobbySeat::ready).count();
        Component body = labeled(player, "mahjongpaper.dialog.table.rule", "Rules",
                ruleProfile(player, state.ruleId(), state.profileId()), NamedTextColor.WHITE)
                .appendNewline().append(labeled(player, "mahjongpaper.dialog.table.seats", "Seats",
                        state.occupiedSeatCount() + "/" + state.seats().size()
                                + " · " + t(player, "mahjongpaper.dialog.table.ready_count",
                                        "%s ready", ready),
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
                ruleProfile(player, match.binding().rulePack().ruleId(), match.binding().profile()),
                NamedTextColor.WHITE);
        String phase = projection == null ? match.actor().snapshot().lifecycle().name()
                : projection.publicView().phase();
        body = body.appendNewline().append(labeled(player,
                "mahjongpaper.dialog.attribute.phase", "Phase", semanticValue(player, phase),
                NamedTextColor.WHITE));
        for (TableParticipant participant : match.participants().stream()
                .sorted(Comparator.comparing(value -> value.seat().map(SeatId::value).orElse(99)))
                .toList()) {
            body = body.appendNewline().append(Component.text(
                    participant.seat().map(seat -> seatName(player, seat)).orElse("•") + " · ",
                    NamedTextColor.GOLD)).append(Component.text(
                    playerName(participant.playerId()) + " · "
                            + semanticValue(player, participant.role().name()),
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

    Component attribute(
            Player player, java.util.List<TableParticipant> participants, String key, String value) {
        return Component.text(attributeName(player, key) + ": ", NamedTextColor.GRAY)
                .append(Component.text(
                        attributeValue(player, participants, key, value), NamedTextColor.WHITE));
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

    private String replacePlayerIds(java.util.List<TableParticipant> participants, String value) {
        String rendered = value;
        for (TableParticipant participant : participants) {
            rendered = rendered.replace(participant.playerId().value().toString(),
                    playerName(participant.playerId()));
        }
        return rendered;
    }

    String ruleName(Player player, RuleId ruleId) {
        return t(player, "mahjongpaper.rule." + ruleId.value(), pretty(ruleId.value()));
    }

    String profileName(Player player, ProfileId profileId, String fallback) {
        return t(player, "mahjongpaper.profile." + profileId.value(), fallback);
    }

    String ruleProfile(Player player, RuleId ruleId, ProfileId profileId) {
        return ruleName(player, ruleId)
                + " / "
                + profileName(player, profileId, pretty(profileId.value()));
    }

    String semanticValue(Player player, String value) {
        String normalized = VALUE_KEY_UNSAFE
                .matcher(value.toLowerCase(Locale.ROOT))
                .replaceAll("_");
        if (normalized.length() > 96 || normalized.isBlank()) {
            return pretty(value);
        }
        if (normalized.equals("east")
                || normalized.equals("south")
                || normalized.equals("west")
                || normalized.equals("north")) {
            return t(player, "mahjongpaper.tile." + normalized, pretty(value));
        }
        if (normalized.equals("wan")
                || normalized.equals("tong")
                || normalized.equals("suo")) {
            return t(player, "mahjongpaper.suit." + normalized, pretty(value));
        }
        return t(player, "mahjongpaper.value." + normalized, pretty(value));
    }

    private String attributeName(Player player, String key) {
        String[] segments = key.split("\\.");
        if (segments.length >= 2 && segments[0].equals("referee")) {
            String label = t(player, "mahjongpaper.dialog.attribute.referee", "Referee");
            if (segments.length == 3) {
                label += " · " + seatValue(player, segments[1]);
            }
            return label + " · " + attributeTerm(player, segments[segments.length - 1]);
        }
        if (segments.length == 2 && segments[0].equals("missing")) {
            return t(player, "mahjongpaper.dialog.attribute.missingSuit", "Missing suit")
                    + " · "
                    + seatValue(player, segments[1]);
        }
        return t(player, "mahjongpaper.dialog.attribute." + key, humanize(key));
    }

    private String attributeTerm(Player player, String key) {
        return t(player, "mahjongpaper.dialog.attribute." + key, humanize(key));
    }

    private String attributeValue(
            Player player, java.util.List<TableParticipant> participants, String key, String value) {
        String rendered = replacePlayerIds(participants, value);
        return switch (key) {
            case "profile" -> {
                try {
                    ProfileId profileId = new ProfileId(rendered);
                    yield profileName(player, profileId, pretty(rendered));
                } catch (IllegalArgumentException invalidProfile) {
                    yield pretty(rendered);
                }
            }
            case "rule" -> {
                try {
                    yield ruleName(player, new RuleId(rendered));
                } catch (IllegalArgumentException invalidRule) {
                    yield pretty(rendered);
                }
            }
            case "dealer", "currentSeat", "currentPlayer", "winner", "winners", "winnerOrder",
                    "tenpai", "nagashi" -> seatValue(player, rendered);
            default -> semanticValue(player, rendered);
        };
    }

    private String seatValue(Player player, String value) {
        return Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .map(token -> seatToken(player, token))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private String seatToken(Player player, String token) {
        try {
            int seat = Integer.parseInt(token);
            if (seat >= 0 && seat < 4) {
                return seatName(player, new SeatId(seat));
            }
        } catch (NumberFormatException ignored) {
            // Named winds are handled by semanticValue below.
        }
        return semanticValue(player, token);
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
