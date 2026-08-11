package top.ellan.mahjong.plugin.match;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.application.automation.PlayerPresencePort;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.plugin.table.LiveTableDirectory;
import top.ellan.mahjong.spi.PlayerId;

/** Active-match facade for manual trustee commands and disconnect takeover. */
public final class MatchAutomationService implements PlayerPresencePort {
    private final LiveTableDirectory tables;

    public MatchAutomationService(LiveTableDirectory tables) {
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    public CompletionStage<TableActionResult> setAutomated(
            PlayerId playerId, boolean enabled) {
        Objects.requireNonNull(playerId, "playerId");
        StartedRulePackMatch match = tables.findByPlayer(playerId).orElse(null);
        if (match == null || match.participants().stream().noneMatch(participant ->
                participant.playerId().equals(playerId)
                        && participant.role() == ParticipantRole.PLAYER
                        && participant.seat().isPresent())) {
            return CompletableFuture.completedFuture(new TableActionResult(
                    TableActionCode.REJECTED_BY_RULES, 0, "active-seated-player-required"));
        }
        return match.actor().setAutomated(playerId, enabled);
    }

    @Override
    public void connected(PlayerId playerId) {
        setIfActive(playerId, false);
    }

    @Override
    public void disconnected(PlayerId playerId) {
        setIfActive(playerId, true);
    }

    private void setIfActive(PlayerId playerId, boolean enabled) {
        tables.findByPlayer(Objects.requireNonNull(playerId, "playerId"))
                .ifPresent(match -> match.actor().setAutomated(playerId, enabled));
    }
}
