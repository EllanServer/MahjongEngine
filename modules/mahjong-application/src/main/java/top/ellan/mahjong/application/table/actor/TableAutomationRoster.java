package top.ellan.mahjong.application.table.actor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;

/** Actor-owned fixed-bot and transient-trustee roster in physical seat order. */
final class TableAutomationRoster {
    private final List<TableParticipant> seated;
    private final Set<PlayerId> fixedBots;
    private final Set<PlayerId> trustees = new HashSet<>();
    private final Set<PlayerId> oneShot = new HashSet<>();
    private volatile Set<PlayerId> publishedTrustees = Set.of();

    TableAutomationRoster(List<TableParticipant> participants) {
        Objects.requireNonNull(participants, "participants");
        seated = participants.stream()
                .filter(participant -> participant.seat().isPresent())
                .sorted(java.util.Comparator.comparingInt(
                        participant -> participant.seat().orElseThrow().value()))
                .toList();
        fixedBots = seated.stream()
                .filter(participant -> participant.role() == ParticipantRole.BOT)
                .map(TableParticipant::playerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    Update update(PlayerId playerId, boolean enabled) {
        Objects.requireNonNull(playerId, "playerId");
        TableParticipant participant = seated.stream()
                .filter(value -> value.playerId().equals(playerId))
                .findFirst()
                .orElse(null);
        if (participant == null || participant.role() != ParticipantRole.PLAYER) {
            return new Update(false, false, "seated-human-required");
        }
        boolean changed = enabled ? trustees.add(playerId) : trustees.remove(playerId);
        if (!enabled) {
            changed |= oneShot.remove(playerId);
        }
        if (changed) {
            publishedTrustees = Set.copyOf(trustees);
        }
        return new Update(
                true,
                changed,
                enabled ? (changed ? "takeover-enabled" : "takeover-already-enabled")
                        : (changed ? "takeover-disabled" : "takeover-already-disabled"));
    }

    List<PlayerId> automatedPlayers() {
        if (fixedBots.isEmpty() && trustees.isEmpty() && oneShot.isEmpty()) {
            return List.of();
        }
        return seated.stream()
                .map(TableParticipant::playerId)
                .filter(player ->
                        fixedBots.contains(player)
                                || trustees.contains(player)
                                || oneShot.contains(player))
                .toList();
    }

    boolean enableOneShot(PlayerId playerId) {
        Objects.requireNonNull(playerId, "playerId");
        boolean human = seated.stream().anyMatch(
                participant -> participant.playerId().equals(playerId)
                        && participant.role() == ParticipantRole.PLAYER);
        return human && !trustees.contains(playerId) && oneShot.add(playerId);
    }

    boolean completeOneShot(PlayerId playerId) {
        return oneShot.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    boolean isOneShot(PlayerId playerId) {
        return oneShot.contains(Objects.requireNonNull(playerId, "playerId"));
    }

    boolean isAutomated(PlayerId playerId) {
        return fixedBots.contains(playerId) || publishedTrustees.contains(playerId);
    }

    record Update(boolean accepted, boolean changed, String reasonCode) {}
}
