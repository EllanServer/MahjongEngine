package top.ellan.mahjong.application.table.actor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.application.persistence.MatchEventRecord;
import top.ellan.mahjong.application.persistence.OutboxHealth;
import top.ellan.mahjong.application.persistence.PersistenceOutbox;
import top.ellan.mahjong.application.persistence.SnapshotWrite;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.TableLifecycle;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.TransitionDisposition;

/** Builds and offers an accepted transition without leaking persistence mechanics into the actor. */
final class AcceptedTransitionWriter {
    private final Clock clock;
    private final MatchEventFactory events;

    AcceptedTransitionWriter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        events = new MatchEventFactory(clock);
    }

    TransitionWrite offer(
            PersistenceOutbox outbox,
            MatchBinding binding,
            long currentRevision,
            long lastEventSequence,
            PlayerId actor,
            RuleAction action,
            RuleComputation computed) {
        RuleTransition transition = computed.transition();
        if (!outbox.canAccept(transition.events().size())) {
            return new TransitionWrite(
                    TransitionWriteStatus.CAPACITY_REJECTED,
                    outbox.health(),
                    lastEventSequence,
                    "persistence-hard-capacity");
        }
        long resultingSequence = lastEventSequence + transition.events().size();
        List<MatchEventRecord> records =
                events.create(
                        binding,
                        actor,
                        action,
                        transition.events(),
                        computed.beforeHash(),
                        computed.afterHash(),
                        currentRevision + 1,
                        lastEventSequence);
        Optional<SnapshotWrite> snapshot =
                computed.snapshot()
                        .map(
                                value ->
                                        new SnapshotWrite(
                                                binding.matchId(),
                                                currentRevision + 1,
                                                Instant.now(clock),
                                                value,
                                                Optional.of(
                                                        transition.disposition()
                                                                        == TransitionDisposition.MATCH_ENDED
                                                                ? TableLifecycle.FINISHED
                                                                : TableLifecycle.ACTIVE)));
        try {
            OutboxHealth health = outbox.offer(records, snapshot);
            return new TransitionWrite(
                    TransitionWriteStatus.ACCEPTED,
                    health,
                    resultingSequence,
                    "");
        } catch (RuntimeException failure) {
            return new TransitionWrite(
                    TransitionWriteStatus.FAILED,
                    outbox.health(),
                    lastEventSequence,
                    "outbox-" + failure.getClass().getSimpleName());
        }
    }
}
