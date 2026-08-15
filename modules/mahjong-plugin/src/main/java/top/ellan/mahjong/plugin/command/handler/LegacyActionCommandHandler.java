package top.ellan.mahjong.plugin.command.handler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.plugin.command.CommandSupport;
import top.ellan.mahjong.plugin.command.SubcommandHandler;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.spi.ActionPresentation;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;

/**
 * v1.5 command fallback for in-match actions. The authoritative input remains the CraftEngine
 * action row; these commands resolve the matching currently-authorized token, so stale or
 * ambiguous commands fail closed exactly like a stale click.
 */
public final class LegacyActionCommandHandler implements SubcommandHandler {
    private static final Set<String> NAMES =
            Set.of("riichi", "tsumo", "ron", "pon", "minkan", "chii", "kan", "skip", "kyuushu");

    private final CommandSupport support;

    public LegacyActionCommandHandler(CommandSupport support) {
        this.support = java.util.Objects.requireNonNull(support, "support");
    }

    @Override
    public Set<String> names() {
        return NAMES;
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        Player player = support.requirePlayer(sender);
        String command = arguments[0].toLowerCase(Locale.ROOT);
        validateArity(command, arguments);
        PlayerId playerId = new PlayerId(player.getUniqueId());
        StartedRulePackMatch match =
                support.runtime()
                        .liveTables()
                        .findByPlayer(playerId)
                        .orElseThrow(
                                () ->
                                        CommandSupport.failure(
                                                "mahjongpaper.command.not_in_active_match",
                                                "You are not in an active match."));
        TableProjection projection =
                match.actor()
                        .latestProjection()
                        .orElseThrow(
                                () ->
                                        CommandSupport.failure(
                                                "mahjongpaper.command.variant_action_unavailable",
                                                "That action is not available right now."));
        List<AuthorizedAction> candidates =
                candidates(command, arguments, playerId, projection);
        AuthorizedAction selected =
                candidates.stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        CommandSupport.failure(
                                                "mahjongpaper.command.variant_action_unavailable",
                                                "That action is not available right now."));
        support.complete(
                sender,
                match.actor().submit(playerId, selected.token()),
                result -> CommandSupport.message(
                        "mahjongpaper.command.action_result",
                        "%s - revision %s - %s",
                        result.code(),
                        result.revision(),
                        result.reasonCode()));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        StartedRulePackMatch match =
                support.runtime()
                        .liveTables()
                        .findByPlayer(new PlayerId(player.getUniqueId()))
                        .orElse(null);
        if (match == null) {
            return List.of();
        }
        TableProjection projection = match.actor().latestProjection().orElse(null);
        if (projection == null) {
            return List.of();
        }
        PlayerId playerId = new PlayerId(player.getUniqueId());
        List<AuthorizedAction> actions =
                projection.authorizedActions().getOrDefault(playerId, List.of());
        String command = arguments[0].toLowerCase(Locale.ROOT);
        List<String> labels = new ArrayList<>();
        for (AuthorizedAction action : actions) {
            String label = action.legalAction().actionPresentation().labelKey();
            if (labelMatches(command, label)) {
                labels.add(label.substring("action.".length()));
            }
        }
        return labels.isEmpty()
                ? List.of()
                : CommandSupport.filter(
                        arguments.length > 1 ? arguments[arguments.length - 1] : "", labels);
    }

    private List<AuthorizedAction> candidates(
            String command,
            String[] arguments,
            PlayerId playerId,
            TableProjection projection) {
        List<AuthorizedAction> actions =
                projection.authorizedActions().getOrDefault(playerId, List.of());
        List<AuthorizedAction> matches = new ArrayList<>();
        for (AuthorizedAction action : actions) {
            if (labelMatches(command, action.legalAction().actionPresentation().labelKey())) {
                matches.add(action);
            }
        }
        if (matches.size() <= 1) {
            return matches;
        }
        return switch (command) {
            case "riichi" -> selectHandIndex(matches, arguments[1], projection, playerId);
            case "chii" -> selectTiles(matches, arguments[1], arguments[2]);
            case "kan" -> selectTiles(matches, arguments[1]);
            default -> matches;
        };
    }

    private static List<AuthorizedAction> selectHandIndex(
            List<AuthorizedAction> actions,
            String rawIndex,
            TableProjection projection,
            PlayerId playerId) {
        int index;
        try {
            index = Integer.parseInt(rawIndex);
        } catch (NumberFormatException invalid) {
            return List.of();
        }
        if (index < 0) {
            return List.of();
        }
        var view = projection.privateViews().get(playerId);
        if (view == null) {
            return actions;
        }
        List<RuleViewTile> hand =
                view.tiles().stream()
                        .filter(tile -> tile.zone() == RuleViewZone.HAND)
                        .sorted(Comparator.comparingInt(RuleViewTile::index))
                        .toList();
        if (index >= hand.size()) {
            return List.of();
        }
        String visual = hand.get(index).visualId().value();
        return actions.stream()
                .filter(
                        action ->
                                tileLabel(action.legalAction().actionPresentation())
                                        .filter(label -> label.contains(":" + visual)
                                                || label.contains(":tile." + visual))
                                        .isPresent())
                .toList();
    }

    private static List<AuthorizedAction> selectTiles(
            List<AuthorizedAction> actions, String... rawTiles) {
        List<String> needles = new ArrayList<>(rawTiles.length);
        for (String raw : rawTiles) {
            String normalized = raw.toLowerCase(Locale.ROOT).replace(' ', '_');
            if (normalized.isBlank()) {
                return List.of();
            }
            needles.add(":" + normalized);
        }
        return actions.stream()
                .filter(
                        action ->
                                needles.stream()
                                        .allMatch(
                                                needle -> {
                                                    String label =
                                                            action.legalAction()
                                                                    .actionPresentation()
                                                                    .labelKey();
                                                    return label.contains(needle)
                                                            || label.contains(":tile." + needle.substring(1));
                                                }))
                .toList();
    }

    private static Optional<String> tileLabel(ActionPresentation presentation) {
        String label = presentation.labelKey();
        int colon = label.indexOf(':');
        return colon < 0 ? Optional.empty() : Optional.of(label.substring(colon + 1));
    }

    private static boolean labelMatches(String command, String label) {
        return switch (command) {
            case "riichi" -> label.startsWith("action.discard_riichi");
            case "tsumo" -> label.startsWith("action.declare_tsumo")
                    || label.startsWith("action.self_draw_win");
            case "ron" -> label.startsWith("action.ron") || label.startsWith("action.hu");
            case "pon" -> label.startsWith("action.pon") || label.startsWith("action.pung");
            case "minkan" -> label.startsWith("action.minkan")
                    || label.startsWith("action.direct_kong");
            case "chii" -> label.startsWith("action.chii") || label.startsWith("action.chow");
            case "kan" -> label.startsWith("action.concealed_kong")
                    || label.startsWith("action.added_kong");
            case "skip" -> label.startsWith("action.skip") || label.startsWith("action.pass");
            case "kyuushu" -> label.startsWith("action.declare_nine_terminals");
            default -> false;
        };
    }

    private static void validateArity(String command, String[] arguments) {
        boolean valid =
                switch (command) {
                    case "riichi", "kan" -> arguments.length == 2;
                    case "chii" -> arguments.length == 3;
                    default -> arguments.length == 1;
                };
        if (!valid) {
            throw CommandSupport.usage(usage(command));
        }
    }

    private static String usage(String command) {
        return switch (command) {
            case "riichi" -> "/mahjong riichi <index>";
            case "kan" -> "/mahjong kan <tile>";
            case "chii" -> "/mahjong chii <tileA> <tileB>";
            default -> "/mahjong " + command;
        };
    }
}
