package top.ellan.mahjong.plugin.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.lobby.runtime.HostedLobby;
import top.ellan.mahjong.application.projection.SceneProjectionPort;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.craftengine.port.TableDialogPort;
import top.ellan.mahjong.domain.lobby.LobbySeat;
import top.ellan.mahjong.domain.lobby.TableLobby;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.plugin.i18n.LocalizedMessageCatalog;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.spi.AuthorizedAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RulePackDescriptor;
import top.ellan.mahjong.spi.RuleProfileDescriptor;
import top.ellan.mahjong.spi.SeatId;

/** Native Dialog controller for table management, rule configuration, and settlement review. */
public final class MahjongDialogService
        implements TableDialogPort, SceneProjectionPort, AutoCloseable {
    private static final int SETTLEMENT_PAGE_SIZE = 9;
    private final MahjongPaperPlugin plugin;
    private final MahjongRuntime runtime;
    private final DialogContent content;
    private final LobbySetupDialogs setupDialogs;
    private final TableLifecycleDialogs lifecycleDialogs;
    private final ConcurrentHashMap<TableId, String> observedPhases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableId, TableProjection> settlements =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public MahjongDialogService(
            MahjongPaperPlugin plugin,
            MahjongRuntime runtime,
            LocalizedMessageCatalog messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        content = new DialogContent(plugin, Objects.requireNonNull(messages, "messages"));
        setupDialogs = new LobbySetupDialogs(this, runtime);
        lifecycleDialogs = new TableLifecycleDialogs(this, runtime);
    }

    @Override
    public void open(Player player, TableId tableId) {
        openTable(player, Optional.of(tableId));
    }

    public void openTable(Player player, Optional<TableId> requested) {
        Objects.requireNonNull(player, "player");
        TableId tableId = requested.orElseGet(() -> currentTable(player).orElse(null));
        if (tableId == null) {
            tell(player, "mahjongpaper.command.not_at_table", "You do not belong to a table.",
                    NamedTextColor.RED);
            return;
        }
        HostedLobby lobby = runtime.lobbyTables().find(tableId).orElse(null);
        if (lobby != null) {
            show(player, lobbyDialog(player, lobby));
            return;
        }
        StartedRulePackMatch match = runtime.liveTables().find(tableId).orElse(null);
        if (match != null) {
            show(player, matchDialog(player, match));
            return;
        }
        tell(player, "mahjongpaper.command.unknown_table", "Unknown table.", NamedTextColor.RED);
    }

    public void openSettlement(Player player, Optional<TableId> requested) {
        TableId tableId = requested.orElseGet(() -> currentTable(player).orElse(null));
        StartedRulePackMatch match = tableId == null
                ? null
                : runtime.liveTables().find(tableId).orElse(null);
        if (match == null) {
            tell(player, "mahjongpaper.dialog.settlement.unavailable",
                    "No live match is available for settlement review.", NamedTextColor.RED);
            return;
        }
        TableProjection projection = settlements.get(tableId);
        if (projection == null) {
            projection = match.actor().latestProjection().orElse(null);
        }
        if (projection == null) {
            tell(player, "mahjongpaper.dialog.settlement.unavailable",
                    "Settlement data is not available yet.", NamedTextColor.RED);
            return;
        }
        show(player, settlementDialog(player, match, projection, 0));
    }

    @Override
    public void publish(TableProjection projection) {
        if (closed.get() || projection.lifecycle() == TableLifecycle.LOBBY) {
            return;
        }
        String phase = projection.publicView().phase().toUpperCase(Locale.ROOT);
        String previous = observedPhases.put(projection.tableId(), phase);
        if (!boundary(phase) || previous == null || boundary(previous)) {
            return;
        }
        settlements.put(projection.tableId(), projection);
        List<PlayerId> viewers = runtime.liveTables().find(projection.tableId())
                .map(match -> match.participants().stream()
                        .filter(participant -> participant.role() != ParticipantRole.BOT)
                        .map(participant -> participant.playerId()).toList())
                .orElseGet(() -> List.copyOf(projection.privateViews().keySet()));
        for (PlayerId playerId : viewers) {
            Player player = plugin.getServer().getPlayer(playerId.value());
            if (player != null) {
                showLater(player, () -> openSettlement(player, Optional.of(projection.tableId())));
            }
        }
    }

    public void forget(TableId tableId) {
        observedPhases.remove(tableId);
        settlements.remove(tableId);
    }

    private Dialog lobbyDialog(Player player, HostedLobby hosted) {
        TableLobby state = hosted.state();
        PlayerId viewer = id(player);
        boolean owner = state.ownerId().equals(viewer);
        List<ActionButton> actions = new ArrayList<>();
        state.seatOf(viewer).ifPresentOrElse(
                seat -> actions.add(button(player,
                        state.seats().get(seat.value()).ready()
                                ? "mahjongpaper.action.unready" : "mahjongpaper.action.ready",
                        state.seats().get(seat.value()).ready() ? "Unready" : "Ready",
                        NamedTextColor.GREEN,
                        callback(ignored -> lobbyAction(player,
                                runtime.lobbyUseCases().toggleReady(viewer), hosted.tableId(), true)))),
                () -> actions.add(button(player,
                        state.spectators().contains(viewer)
                                ? "mahjongpaper.dialog.button.unspectate"
                                : "mahjongpaper.dialog.button.spectate",
                        state.spectators().contains(viewer) ? "Stop spectating" : "Spectate",
                        NamedTextColor.AQUA,
                        callback(ignored -> lobbyAction(
                                player,
                                state.spectators().contains(viewer)
                                        ? runtime.lobbyUseCases().unspectate(viewer)
                                        : runtime.lobbyUseCases().spectate(hosted.tableId(), viewer),
                                hosted.tableId(), true)))));
        actions.add(button(player, "mahjongpaper.dialog.button.seats", "Seats & bots",
                NamedTextColor.AQUA, callback(ignored -> openSeats(player, hosted.tableId()))));
        actions.add(button(player, "mahjongpaper.dialog.button.rules", "Rule settings",
                NamedTextColor.GOLD, callback(ignored -> openRules(player, hosted.tableId()))));
        if (owner) {
            actions.add(button(player, "mahjongpaper.action.start", "Start match",
                    NamedTextColor.GREEN, callback(ignored -> lobbyAction(player,
                            runtime.lobbyUseCases().start(viewer), hosted.tableId(), false))));
        }
        if (state.contains(viewer)) {
            actions.add(button(player, "mahjongpaper.action.leave", "Leave table",
                    NamedTextColor.RED, callback(ignored -> confirmLeave(player, hosted.tableId()))));
        }
        if (owner || player.hasPermission("mahjongpaper.admin")) {
            actions.add(button(player, "mahjongpaper.dialog.button.remove", "Remove table",
                    NamedTextColor.RED,
                    callback(ignored -> lifecycleDialogs.confirmRemove(player, hosted.tableId()))));
        }
        return DialogUi.multi(title(player, "mahjongpaper.dialog.table.title", "Table · %s", shortId(hosted.tableId())),
                lobbyBody(player, state), List.of(), actions, 2, closeButton(player));
    }

    private void openSeats(Player player, TableId tableId) {
        setupDialogs.openSeats(player, tableId);
    }

    private void openRules(Player player, TableId tableId) {
        setupDialogs.openRules(player, tableId);
    }

    private Dialog matchDialog(Player player, StartedRulePackMatch match) {
        TableProjection projection = match.actor().latestProjection().orElse(null);
        List<ActionButton> actions = new ArrayList<>();
        actions.add(button(player, "mahjongpaper.dialog.button.settlement", "Settlement details",
                NamedTextColor.GOLD, callback(ignored -> openSettlement(player, Optional.of(match.tableId())))));
        boolean seatedHuman = match.participants().stream().anyMatch(participant ->
                participant.playerId().equals(id(player)) && participant.role() == ParticipantRole.PLAYER);
        boolean spectator = match.participants().stream().anyMatch(participant ->
                participant.playerId().equals(id(player))
                        && participant.role() == ParticipantRole.SPECTATOR);
        boolean departing = runtime.isDeparting(match.tableId(), id(player));
        if (seatedHuman) {
            boolean automated = runtime.automationEnabled(id(player));
            actions.add(button(player, automated ? "mahjongpaper.dialog.button.automation_off"
                            : "mahjongpaper.dialog.button.automation_on",
                    automated ? "Disable trustee" : "Enable trustee",
                    automated ? NamedTextColor.AQUA : NamedTextColor.YELLOW,
                    callback(ignored -> matchAction(player,
                            runtime.setAutomation(id(player), !automated), match.tableId()))));
        }
        if (seatedHuman && !departing) {
            actions.add(button(player, "mahjongpaper.action.leave", "Leave after match",
                    NamedTextColor.RED,
                    callback(ignored -> lifecycleDialogs.confirmLiveLeave(player, match.tableId()))));
        } else if (spectator && !departing) {
            actions.add(button(player, "mahjongpaper.dialog.button.unspectate", "Stop spectating",
                    NamedTextColor.RED,
                    callback(ignored -> lobbyAction(
                            player, runtime.unspectate(id(player)), match.tableId(), false))));
        }
        nextHandAction(projection, id(player)).ifPresent(action -> actions.add(button(player,
                "mahjongpaper.action.start_next_hand", "Next hand", NamedTextColor.GREEN,
                callback(ignored -> matchAction(player,
                        match.actor().submit(id(player), action.token()), match.tableId())))));
        if (player.hasPermission("mahjongpaper.admin")) {
            actions.add(button(player, "mahjongpaper.dialog.button.remove", "Remove table",
                    NamedTextColor.RED,
                    callback(ignored -> lifecycleDialogs.confirmRemove(player, match.tableId()))));
        }
        Component body = matchBody(player, match, projection);
        return DialogUi.multi(title(player, "mahjongpaper.dialog.table.title", "Table · %s", shortId(match.tableId())),
                body, List.of(), actions, 2, closeButton(player));
    }

    private Dialog settlementDialog(
            Player player, StartedRulePackMatch match, TableProjection projection, int requestedPage) {
        List<Map.Entry<String, String>> entries = projection.publicView().attributes().entrySet()
                .stream().sorted(Map.Entry.comparingByKey()).toList();
        int pages = Math.max(1, (entries.size() + SETTLEMENT_PAGE_SIZE - 1) / SETTLEMENT_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * SETTLEMENT_PAGE_SIZE;
        int end = Math.min(start + SETTLEMENT_PAGE_SIZE, entries.size());
        Component body = labeled(player, "mahjongpaper.dialog.attribute.phase", "Phase",
                content.semanticValue(player, projection.publicView().phase()), NamedTextColor.WHITE);
        for (int index = start; index < end; index++) {
            Map.Entry<String, String> entry = entries.get(index);
            body = body.appendNewline().append(
                    content.attribute(player, match, entry.getKey(), entry.getValue()));
        }
        if (entries.isEmpty()) {
            body = body.appendNewline().append(textComponent(player,
                    "mahjongpaper.dialog.settlement.empty", "No settlement fields are available yet.",
                    NamedTextColor.GRAY));
        }
        body = body.appendNewline().append(textComponent(player,
                "mahjongpaper.dialog.settlement.page", "Page %s/%s", NamedTextColor.DARK_GRAY,
                page + 1, pages));
        List<ActionButton> actions = new ArrayList<>();
        if (page > 0) {
            int previous = page - 1;
            actions.add(button(player, "mahjongpaper.dialog.button.previous", "Previous",
                    NamedTextColor.YELLOW, callback(ignored -> show(player,
                            settlementDialog(player, match, projection, previous)))));
        }
        if (page + 1 < pages) {
            int next = page + 1;
            actions.add(button(player, "mahjongpaper.dialog.button.next", "Next",
                    NamedTextColor.YELLOW, callback(ignored -> show(player,
                            settlementDialog(player, match, projection, next)))));
        }
        nextHandAction(projection, id(player)).ifPresent(action -> actions.add(button(player,
                "mahjongpaper.action.start_next_hand", "Next hand", NamedTextColor.GREEN,
                callback(ignored -> matchAction(player,
                        match.actor().submit(id(player), action.token()), match.tableId())))));
        actions.add(backButton(player, ignored -> openTable(player, Optional.of(match.tableId()))));
        return DialogUi.multi(title(player, "mahjongpaper.dialog.settlement.title",
                        "Settlement · %s", shortId(match.tableId())), body, List.of(), actions, 2,
                closeButton(player));
    }

    private void confirmLeave(Player player, TableId tableId) {
        show(player, DialogUi.confirmation(
                title(player, "mahjongpaper.dialog.leave.title", "Leave table?"),
                textComponent(player, "mahjongpaper.dialog.leave.body",
                        "Your seat will become available to another player.", NamedTextColor.YELLOW),
                button(player, "mahjongpaper.dialog.button.confirm", "Confirm", NamedTextColor.RED,
                        callback(ignored -> lobbyAction(player,
                                runtime.lobbyUseCases().leave(id(player)), tableId, false))),
                button(player, "mahjongpaper.dialog.button.cancel", "Cancel", NamedTextColor.GRAY,
                        callback(ignored -> openTable(player, Optional.of(tableId))))));
    }

    void lobbyAction(
            Player player, CompletionStage<TableActionResult> stage, TableId tableId,
            boolean reopen) {
        complete(player, stage, result -> {
            if (reopen) {
                openTable(player, Optional.of(tableId));
            }
            return null;
        });
    }

    private void matchAction(Player player, CompletionStage<TableActionResult> stage, TableId tableId) {
        complete(player, stage, result -> {
            openTable(player, Optional.of(tableId));
            return null;
        });
    }

    private void complete(
            Player player, CompletionStage<TableActionResult> stage,
            Function<TableActionResult, Void> after) {
        stage.whenComplete((result, failure) -> {
            if (failure != null) {
                failure(player, failure);
                return;
            }
            boolean accepted = result.code() == TableActionCode.ACCEPTED_MEMORY;
            String reason = t(player,
                    "mahjongpaper.dialog.result." + result.reasonCode().replace('-', '_'),
                    pretty(result.reasonCode()));
            tell(player, "mahjongpaper.dialog.feedback.action_result", "%s",
                    accepted ? NamedTextColor.GREEN : NamedTextColor.RED,
                    reason);
            after.apply(result);
        });
    }

    private Component lobbyBody(Player player, TableLobby state) {
        return content.lobbyBody(player, state);
    }

    Component lobbySeatLines(Player player, TableLobby state) {
        return content.lobbySeatLines(player, state);
    }

    private Component matchBody(
            Player player, StartedRulePackMatch match, TableProjection projection) {
        return content.matchBody(player, match, projection);
    }

    private Optional<AuthorizedAction> nextHandAction(TableProjection projection, PlayerId player) {
        if (projection == null) {
            return Optional.empty();
        }
        return projection.authorizedActions().getOrDefault(player, List.of()).stream()
                .filter(action -> "start_next_hand".equals(action.legalAction().action().type()))
                .findFirst();
    }

    private Optional<TableId> currentTable(Player player) {
        PlayerId playerId = id(player);
        return runtime.lobbyTables().findByPlayer(playerId).map(HostedLobby::tableId)
                .or(() -> runtime.liveTables().findByPlayer(playerId)
                        .map(StartedRulePackMatch::tableId));
    }

    void show(Player player, Dialog dialog) {
        showLater(player, () -> {
            if (!closed.get() && player.isOnline()) {
                player.showDialog(dialog);
            }
        });
    }

    private void showLater(Player player, Runnable action) {
        if (!closed.get()) {
            player.getScheduler().run(plugin, ignored -> action.run(), null);
        }
    }

    ActionButton closeButton(Player player) {
        return button(player, "mahjongpaper.dialog.button.close", "Close",
                NamedTextColor.GRAY, callback(ignored -> {}));
    }

    ActionButton backButton(Player player, java.util.function.Consumer<Player> action) {
        return button(player, "mahjongpaper.dialog.button.back", "Back",
                NamedTextColor.YELLOW, callback(action));
    }

    ActionButton button(
            Player player, String key, String fallback, NamedTextColor color,
            DialogActionCallback callback, Object... arguments) {
        return DialogUi.button(Component.text(t(player, key, fallback, arguments), color), callback);
    }

    ActionButton button(
            Player player, String label, NamedTextColor color, DialogActionCallback callback) {
        return DialogUi.button(Component.text(label, color), callback);
    }

    DialogActionCallback callback(java.util.function.Consumer<Player> action) {
        return (response, audience) -> player(audience).ifPresent(action);
    }

    Component title(Player player, String key, String fallback, Object... arguments) {
        return content.title(player, key, fallback, arguments);
    }

    Component labeled(
            Player player, String key, String fallback, String value, NamedTextColor valueColor) {
        return content.labeled(player, key, fallback, value, valueColor);
    }

    Component textComponent(
            Player player, String key, String fallback, NamedTextColor color, Object... arguments) {
        return content.text(player, key, fallback, color, arguments);
    }

    private String t(Player player, String key, String fallback, Object... arguments) {
        return content.t(player, key, fallback, arguments);
    }

    void tell(
            Player player, String key, String fallback, NamedTextColor color, Object... arguments) {
        Component message = textComponent(player, key, fallback, color, arguments);
        showLater(player, () -> player.sendMessage(message));
    }

    void failure(Player player, Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        tell(player, "mahjongpaper.command.failed", "Failed: %s", NamedTextColor.RED,
                current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage());
    }

    String ruleName(Player player, top.ellan.mahjong.spi.RuleId ruleId) {
        return content.ruleName(player, ruleId);
    }

    String profileName(Player player, top.ellan.mahjong.spi.ProfileId profileId, String fallback) {
        return content.profileName(player, profileId, fallback);
    }

    String ruleProfile(Player player, top.ellan.mahjong.spi.RuleId ruleId,
            top.ellan.mahjong.spi.ProfileId profileId) {
        return content.ruleProfile(player, ruleId, profileId);
    }

    String seatName(Player player, SeatId seat) {
        return content.seatName(player, seat);
    }

    private static Optional<Player> player(Audience audience) {
        return audience instanceof Player player ? Optional.of(player) : Optional.empty();
    }

    static PlayerId id(Player player) {
        return new PlayerId(player.getUniqueId());
    }

    private static boolean boundary(String phase) {
        return phase.contains("BETWEEN_HAND") || phase.contains("BETWEEN_ROUND")
                || phase.equals("ENDED") || phase.equals("FINISHED");
    }

    private static String configurationText(Map<String, String> configuration) {
        return DialogContent.configurationText(configuration);
    }

    private static String shortId(TableId tableId) {
        return DialogContent.shortId(tableId);
    }

    private static String pretty(String value) {
        return DialogContent.pretty(value);
    }

    private static String humanize(String value) {
        return DialogContent.humanize(value);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            observedPhases.clear();
            settlements.clear();
        }
    }
}
