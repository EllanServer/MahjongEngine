package top.ellan.mahjong.application.table.actor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.ScheduledRuleAction;
import top.ellan.mahjong.spi.TransitionDisposition;

/** Executes and validates pure provider calls away from the actor scheduling loop. */
final class RuleComputationEngine {
    private static final long SNAPSHOT_ACTION_INTERVAL = 32L;

    private final RulePackProvider provider;
    private final List<TableParticipant> participants;
    private final Set<PlayerId> participantIds;
    private final TableActorConfig limits;

    RuleComputationEngine(
            RulePackProvider provider,
            List<TableParticipant> participants,
            TableActorConfig limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.participants = List.copyOf(participants);
        participantIds = this.participants.stream()
                .map(TableParticipant::playerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    RuleComputation frameOnly(RuleState state, long revision) {
        String hash = provider.stateHash(state);
        return new RuleComputation(
                state,
                null,
                hash,
                hash,
                Optional.empty(),
                buildFrame(state, revision));
    }

    RuleComputation transition(
            RuleState state,
            PlayerId actor,
            RuleAction action,
            long revision,
            long startingSequence,
            long nextAcceptedAction) {
        String beforeHash = provider.stateHash(state);
        RuleTransition transition = provider.transition(state, actor, action);
        Objects.requireNonNull(transition, "provider returned null transition");
        validateTransition(state, transition);
        long targetRevision = transition.accepted() ? revision + 1 : revision;
        RuleState targetState = transition.nextState();
        String afterHash = provider.stateHash(targetState);
        long resultingSequence = startingSequence + transition.events().size();
        Optional<RuleStateSnapshot> snapshot =
                snapshotDue(transition, nextAcceptedAction)
                        ? Optional.of(provider.snapshot(targetState, resultingSequence))
                        : Optional.empty();
        return new RuleComputation(
                targetState,
                transition,
                beforeHash,
                afterHash,
                snapshot,
                buildFrame(targetState, targetRevision));
    }

    private void validateTransition(
            RuleState previousState,
            RuleTransition transition) {
        if (!transition.accepted() && transition.nextState() != previousState) {
            throw new IllegalStateException(
                    "Rejected transition did not return the same state instance");
        }
        if (transition.accepted() && transition.events().isEmpty()) {
            throw new IllegalStateException("Accepted transition emitted no events");
        }
        if (transition.events().size() > limits.maxEventsPerAction()) {
            throw new IllegalStateException("Provider exceeded per-action event limit");
        }
        transition.presentationCues().forEach(cue -> cue.target().ifPresent(target -> {
            if (!participantIds.contains(target)) {
                throw new IllegalStateException(
                        "Provider emitted a presentation cue for a non-participant");
            }
        }));
    }

    private static boolean snapshotDue(
            RuleTransition transition,
            long nextAcceptedAction) {
        return transition.accepted()
                && (nextAcceptedAction % SNAPSHOT_ACTION_INTERVAL == 0
                        || transition.disposition() == TransitionDisposition.ROUND_ENDED
                        || transition.disposition() == TransitionDisposition.MATCH_ENDED);
    }

    private RuleFrame buildFrame(RuleState state, long revision) {
        PublicRuleView publicView = provider.publicView(state, revision);
        if (publicView.stateRevision() != revision) {
            throw new IllegalStateException(
                    "Provider returned a public view for the wrong revision");
        }
        Map<PlayerId, PrivateRuleView> privateViews = new LinkedHashMap<>();
        Map<PlayerId, List<LegalAction>> legalActions = new LinkedHashMap<>();
        for (TableParticipant participant : participants) {
            if (participant.seat().isEmpty()) {
                continue;
            }
            addParticipantFrame(
                    state,
                    revision,
                    participant.playerId(),
                    privateViews,
                    legalActions);
        }
        Optional<ScheduledRuleAction> scheduledAction = Objects.requireNonNull(
                provider.scheduledAction(state), "provider returned null scheduled action");
        scheduledAction.ifPresent(this::validateScheduledActor);
        return new RuleFrame(publicView, privateViews, legalActions, scheduledAction);
    }

    private void validateScheduledActor(ScheduledRuleAction scheduled) {
        boolean seated = participants.stream().anyMatch(participant ->
                participant.seat().isPresent()
                        && participant.playerId().equals(scheduled.actor()));
        if (!seated) {
            throw new IllegalStateException("Provider scheduled an action for an unseated actor");
        }
    }

    private void addParticipantFrame(
            RuleState state,
            long revision,
            PlayerId player,
            Map<PlayerId, PrivateRuleView> privateViews,
            Map<PlayerId, List<LegalAction>> legalActions) {
        PrivateRuleView privateView = provider.privateView(state, player, revision);
        if (!privateView.viewer().equals(player) || privateView.stateRevision() != revision) {
            throw new IllegalStateException("Provider returned an unauthorized private view");
        }
        List<LegalAction> actions = List.copyOf(provider.legalActions(state, player));
        if (actions.size() > limits.maxLegalActionsPerPlayer()) {
            throw new IllegalStateException("Provider exceeded legal-action limit");
        }
        long distinctKeys = actions.stream().map(LegalAction::key).distinct().count();
        if (distinctKeys != actions.size()) {
            throw new IllegalStateException("Provider emitted duplicate legal-action keys");
        }
        privateViews.put(player, privateView);
        legalActions.put(player, actions);
    }
}
