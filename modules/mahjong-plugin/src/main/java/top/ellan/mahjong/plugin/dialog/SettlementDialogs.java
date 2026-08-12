package top.ellan.mahjong.plugin.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import top.ellan.mahjong.application.projection.TableProjection;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.plugin.MahjongPaperPlugin;
import top.ellan.mahjong.plugin.MahjongRuntime;
import top.ellan.mahjong.plugin.match.StartedRulePackMatch;
import top.ellan.mahjong.spi.PlayerId;

/** Per-table settlement snapshots and dialogs; never retains private views or scans server players. */
final class SettlementDialogs implements AutoCloseable {
    private static final int PAGE_SIZE = 9;
    private final MahjongDialogService owner;
    private final MahjongPaperPlugin plugin;
    private final MahjongRuntime runtime;
    private final DialogContent content;
    private final ConcurrentHashMap<TableId, String> observedPhases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TableId, SettlementSnapshot> settlements =
            new ConcurrentHashMap<>();

    SettlementDialogs(
            MahjongDialogService owner,
            MahjongPaperPlugin plugin,
            MahjongRuntime runtime,
            DialogContent content) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.content = Objects.requireNonNull(content, "content");
    }

    void open(Player player, Optional<TableId> requested) {
        TableId tableId = requested.orElseGet(() -> owner.currentTable(player).orElse(null));
        if (tableId == null) {
            unavailable(player);
            return;
        }
        StartedRulePackMatch live = runtime.liveTables().find(tableId).orElse(null);
        SettlementSnapshot snapshot = settlements.get(tableId);
        if (snapshot == null && live != null) {
            snapshot = live.actor().latestProjection()
                    .map(projection -> SettlementSnapshot.from(projection, live.participants()))
                    .orElse(null);
        }
        if (snapshot == null) {
            unavailable(player);
            return;
        }
        if (!snapshot.canView(MahjongDialogService.id(player))) {
            owner.tell(
                    player,
                    "mahjongpaper.dialog.settlement.not_participant",
                    "Only players from this match can view its settlement.",
                    NamedTextColor.RED);
            return;
        }
        owner.show(player, dialog(player, snapshot, 0));
    }

    void publish(TableProjection projection) {
        if (owner.isClosed() || projection.lifecycle() == TableLifecycle.LOBBY) {
            return;
        }
        StartedRulePackMatch match = runtime.liveTables().find(projection.tableId()).orElse(null);
        if (match == null) {
            return;
        }
        String phase = projection.publicView().phase().toUpperCase(Locale.ROOT);
        String previous = observedPhases.put(projection.tableId(), phase);
        if (!boundary(phase)) {
            if (previous == null) {
                settlements.remove(projection.tableId());
            }
            return;
        }
        SettlementSnapshot snapshot = SettlementSnapshot.from(projection, match.participants());
        settlements.put(projection.tableId(), snapshot);
        if (previous == null || boundary(previous)) {
            return;
        }
        for (TableParticipant participant : snapshot.participants()) {
            if (participant.role() == ParticipantRole.BOT) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(participant.playerId().value());
            if (player != null) {
                owner.showLater(player, () -> open(player, Optional.of(projection.tableId())));
            }
        }
    }

    boolean available(TableId tableId, PlayerId viewer) {
        SettlementSnapshot snapshot = settlements.get(tableId);
        return snapshot != null && snapshot.canView(viewer);
    }

    void matchRecycled(TableId tableId) {
        observedPhases.remove(tableId);
    }

    void forget(TableId tableId) {
        observedPhases.remove(tableId);
        settlements.remove(tableId);
    }

    private Dialog dialog(Player player, SettlementSnapshot snapshot, int requestedPage) {
        List<Map.Entry<String, String>> entries = snapshot.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, entries.size());
        Component body = owner.labeled(
                player,
                "mahjongpaper.dialog.attribute.phase",
                "Phase",
                content.semanticValue(player, snapshot.phase()),
                NamedTextColor.WHITE);
        for (int index = start; index < end; index++) {
            Map.Entry<String, String> entry = entries.get(index);
            body = body.appendNewline().append(content.attribute(
                    player,
                    snapshot.participants(),
                    entry.getKey(),
                    entry.getValue()));
        }
        if (entries.isEmpty()) {
            body = body.appendNewline().append(owner.textComponent(
                    player,
                    "mahjongpaper.dialog.settlement.empty",
                    "No settlement fields are available yet.",
                    NamedTextColor.GRAY));
        }
        body = body.appendNewline().append(owner.textComponent(
                player,
                "mahjongpaper.dialog.settlement.page",
                "Page %s/%s",
                NamedTextColor.DARK_GRAY,
                page + 1,
                pages));
        List<ActionButton> actions = new ArrayList<>();
        if (page > 0) {
            int previous = page - 1;
            actions.add(owner.button(
                    player,
                    "mahjongpaper.dialog.button.previous",
                    "Previous",
                    NamedTextColor.YELLOW,
                    owner.callback(ignored -> owner.show(player, dialog(player, snapshot, previous)))));
        }
        if (page + 1 < pages) {
            int next = page + 1;
            actions.add(owner.button(
                    player,
                    "mahjongpaper.dialog.button.next",
                    "Next",
                    NamedTextColor.YELLOW,
                    owner.callback(ignored -> owner.show(player, dialog(player, snapshot, next)))));
        }
        StartedRulePackMatch live = runtime.liveTables().find(snapshot.tableId()).orElse(null);
        if (live != null) {
            owner.nextHandAction(
                            live.actor().latestProjection().orElse(null),
                            MahjongDialogService.id(player))
                    .ifPresent(action -> actions.add(owner.button(
                            player,
                            "mahjongpaper.action.start_next_hand",
                            "Next hand",
                            NamedTextColor.GREEN,
                            owner.callback(ignored -> owner.matchAction(
                                    player,
                                    live.actor().submit(
                                            MahjongDialogService.id(player), action.token()),
                                    live.tableId())))));
        }
        actions.add(owner.backButton(
                player, ignored -> owner.openTable(player, Optional.of(snapshot.tableId()))));
        return DialogUi.multi(
                owner.title(
                        player,
                        "mahjongpaper.dialog.settlement.title",
                        "Settlement · %s",
                        DialogContent.shortId(snapshot.tableId())),
                body,
                List.of(),
                actions,
                2,
                owner.closeButton(player));
    }

    private void unavailable(Player player) {
        owner.tell(
                player,
                "mahjongpaper.dialog.settlement.unavailable",
                "Settlement data is not available yet.",
                NamedTextColor.RED);
    }

    private static boolean boundary(String phase) {
        return phase.contains("BETWEEN_HAND")
                || phase.contains("BETWEEN_ROUND")
                || phase.equals("ENDED")
                || phase.equals("FINISHED");
    }

    @Override
    public void close() {
        observedPhases.clear();
        settlements.clear();
    }

    private record SettlementSnapshot(
            TableId tableId,
            long revision,
            String phase,
            Map<String, String> attributes,
            List<TableParticipant> participants) {
        private SettlementSnapshot {
            Objects.requireNonNull(tableId, "tableId");
            Objects.requireNonNull(phase, "phase");
            attributes = Map.copyOf(new LinkedHashMap<>(attributes));
            participants = List.copyOf(participants);
        }

        static SettlementSnapshot from(
                TableProjection projection, List<TableParticipant> participants) {
            return new SettlementSnapshot(
                    projection.tableId(),
                    projection.revision(),
                    projection.publicView().phase(),
                    projection.publicView().attributes(),
                    participants);
        }

        boolean canView(PlayerId viewer) {
            return participants.stream().anyMatch(participant ->
                    participant.role() != ParticipantRole.BOT
                            && participant.playerId().equals(viewer));
        }
    }
}
