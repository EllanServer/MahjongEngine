package top.ellan.mahjong.table.core;

import top.ellan.mahjong.model.MahjongTile;
import top.ellan.mahjong.presentation.TableFeedbackPolicy;
import top.ellan.mahjong.runtime.PluginTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Owns the short-lived public action announcement and its expiry task. */
final class SessionPublicActionCoordinator {
    private final MahjongTableSession session;
    private PublicActionAnnouncement announcement;
    private PluginTask clearTask;
    private long sequence;

    SessionPublicActionCoordinator(MahjongTableSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    void remember(UUID playerId, String actionKey) {
        this.remember(playerId == null ? List.of() : List.of(playerId), actionKey, List.of());
    }

    void remember(UUID playerId, String actionKey, List<MahjongTile> tiles) {
        this.remember(playerId == null ? List.of() : List.of(playerId), actionKey, tiles);
    }

    void remember(List<UUID> playerIds, String actionKey) {
        this.remember(playerIds, actionKey, List.of());
    }

    private void remember(List<UUID> playerIds, String actionKey, List<MahjongTile> tiles) {
        List<UUID> actors = playerIds == null
            ? List.of()
            : playerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (actors.isEmpty() || actionKey == null || actionKey.isBlank()) {
            return;
        }
        this.announcement = new PublicActionAnnouncement(actors, actionKey, tiles);
        long expectedSequence = ++this.sequence;
        this.cancelClearTask();
        this.clearTask = this.session.plugin().scheduler().runRegionDelayed(this.session.center(), () -> {
            this.clearTask = null;
            if (this.sequence != expectedSequence) {
                return;
            }
            this.announcement = null;
            this.session.render();
        }, TableFeedbackPolicy.announcementDurationTicks(actionKey));
    }

    void clear() {
        this.announcement = null;
        this.cancelClearTask();
    }

    String summary(Locale locale) {
        PublicActionAnnouncement current = this.announcement;
        if (current == null) {
            return "";
        }
        List<String> actorNames = new ArrayList<>(current.playerIds().size());
        for (UUID playerId : current.playerIds()) {
            String actor = this.session.displayName(playerId, locale);
            actorNames.add(actor == null || actor.isBlank()
                ? this.session.plugin().messages().plain(locale, "common.unknown")
                : actor);
        }
        String action = this.actionLabelWithTiles(locale, current);
        if (action.isBlank()) {
            return "";
        }
        return this.session.plugin().messages().plain(
            locale,
            "table.last_action",
            this.session.plugin().messages().tag("player", TableFeedbackPolicy.joinPlayerNames(actorNames)),
            this.session.plugin().messages().tag("action", action)
        );
    }

    private String actionLabelWithTiles(Locale locale, PublicActionAnnouncement current) {
        String action = this.session.plugin().messages().plain(locale, current.actionKey());
        if (current.tiles().isEmpty()) {
            return action;
        }
        String tileLabels = current.tiles().stream()
            .map(tile -> this.session.tileLabelForDisplay(locale, tile.name()))
            .collect(Collectors.joining(" "));
        return tileLabels.isBlank() ? action : action + " " + tileLabels;
    }

    private void cancelClearTask() {
        if (this.clearTask == null) {
            return;
        }
        this.clearTask.cancel();
        this.clearTask = null;
    }

    private record PublicActionAnnouncement(
        List<UUID> playerIds,
        String actionKey,
        List<MahjongTile> tiles
    ) {
        private PublicActionAnnouncement {
            playerIds = playerIds == null ? List.of() : List.copyOf(playerIds);
            tiles = tiles == null ? List.of() : List.copyOf(tiles);
        }
    }
}
