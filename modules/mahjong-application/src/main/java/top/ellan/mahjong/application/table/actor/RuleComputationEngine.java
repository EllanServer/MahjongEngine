package top.ellan.mahjong.application.table.actor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import top.ellan.mahjong.application.table.TableActorConfig;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.AutomatedPlayerActions;
import top.ellan.mahjong.spi.LegalAction;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.PublicRuleView;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleMatchResult;
import top.ellan.mahjong.spi.RulePackProvider;
import top.ellan.mahjong.spi.RulePlayerResult;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;
import top.ellan.mahjong.spi.ScheduledRuleAction;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.TransitionDisposition;

/** Executes and validates pure provider calls away from the actor scheduling loop. */
final class RuleComputationEngine {
    private static final long SNAPSHOT_ACTION_INTERVAL = 32L;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final RulePackProvider provider;
    private final List<TableParticipant> participants;
    private final Set<PlayerId> participantIds;
    private final Map<PlayerId, SeatId> seatedAssignments;
    private final TableActorConfig limits;

    RuleComputationEngine(
            RulePackProvider provider,
            List<TableParticipant> participants,
            TableActorConfig limits) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.participants = participants.stream()
                .sorted(java.util.Comparator.comparingInt(participant ->
                        participant.seat().map(top.ellan.mahjong.spi.SeatId::value)
                                .orElse(Integer.MAX_VALUE)))
                .toList();
        participantIds = this.participants.stream()
                .map(TableParticipant::playerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        LinkedHashMap<PlayerId, SeatId> seats = new LinkedHashMap<>();
        this.participants.forEach(participant ->
                participant.seat().ifPresent(seat -> seats.put(participant.playerId(), seat)));
        seatedAssignments = Map.copyOf(seats);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    RuleComputation frameOnly(
            RuleState state, long revision, List<PlayerId> automatedPlayers) {
        String hash = checkedHash(provider.stateHash(state));
        return new RuleComputation(
                state,
                null,
                hash,
                hash,
                Optional.empty(),
                Optional.empty(),
                buildFrame(state, revision, automatedPlayers));
    }

    RuleComputation transition(
            RuleState state,
            PlayerId actor,
            RuleAction action,
            long revision,
            long startingSequence,
            long nextAcceptedAction,
            List<PlayerId> automatedPlayers) {
        String beforeHash = checkedHash(provider.stateHash(state));
        RuleTransition transition = provider.transition(state, actor, action);
        Objects.requireNonNull(transition, "provider returned null transition");
        validateTransition(state, transition);
        long targetRevision = transition.accepted() ? revision + 1 : revision;
        RuleState targetState = transition.nextState();
        String afterHash = checkedHash(provider.stateHash(targetState));
        long resultingSequence = startingSequence + transition.events().size();
        Optional<RuleStateSnapshot> snapshot = snapshotDue(transition, nextAcceptedAction)
                ? Optional.of(validatedSnapshot(targetState, resultingSequence, afterHash))
                : Optional.empty();
        Optional<RuleMatchResult> matchResult = transition.disposition()
                        == TransitionDisposition.MATCH_ENDED
                ? Objects.requireNonNull(
                        provider.matchResult(targetState),
                        "provider returned null match result")
                : Optional.empty();
        if (transition.disposition() == TransitionDisposition.MATCH_ENDED) {
            validateMatchResult(matchResult.orElseThrow(() ->
                    new IllegalStateException("Provider omitted terminal match result")));
        }
        return new RuleComputation(
                targetState,
                transition,
                beforeHash,
                afterHash,
                snapshot,
                matchResult,
                buildFrame(targetState, targetRevision, automatedPlayers));
    }

    private void validateMatchResult(RuleMatchResult result) {
        if (result.players().size() != seatedAssignments.size()) {
            throw new IllegalStateException("Terminal result does not cover every seated player");
        }
        for (RulePlayerResult player : result.players()) {
            SeatId expected = seatedAssignments.get(player.playerId());
            if (expected == null || !expected.equals(player.seatId())) {
                throw new IllegalStateException(
                        "Terminal result contains an unknown player or mismatched seat");
            }
        }
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

    private RuleFrame buildFrame(
            RuleState state, long revision, List<PlayerId> automatedPlayers) {
        Set<PlayerId> automated = Set.copyOf(automatedPlayers);
        PublicRuleView publicView = Objects.requireNonNull(
                provider.publicView(state, revision), "provider returned null public view");
        if (publicView.stateRevision() != revision) {
            throw new IllegalStateException(
                    "Provider returned a public view for the wrong revision");
        }
        Map<PlayerId, PrivateRuleView> privateViews = new LinkedHashMap<>();
        Map<PlayerId, List<LegalAction>> legalActions = new LinkedHashMap<>();
        java.util.ArrayList<AutomatedPlayerActions> automationCandidates =
                new java.util.ArrayList<>(automated.size());
        for (TableParticipant participant : participants) {
            if (participant.seat().isEmpty()) {
                continue;
            }
            boolean playerProjection = participant.role() == ParticipantRole.PLAYER;
            boolean automatedSeat = automated.contains(participant.playerId());
            if (!playerProjection && !automatedSeat) {
                continue;
            }
            List<LegalAction> actions = validatedLegalActions(state, participant.playerId());
            if (playerProjection) {
                privateViews.put(
                        participant.playerId(),
                        validatedPrivateView(state, revision, participant.playerId()));
                legalActions.put(participant.playerId(), actions);
            }
            if (automatedSeat) {
                automationCandidates.add(
                        new AutomatedPlayerActions(participant.playerId(), actions));
            }
        }
        if (automationCandidates.size() != automated.size()) {
            throw new IllegalStateException("Automation roster contains an unseated actor");
        }
        Optional<ScheduledRuleAction> systemAction = Objects.requireNonNull(
                provider.scheduledAction(state), "provider returned null scheduled action");
        systemAction.ifPresent(this::validateScheduledActor);
        Optional<ScheduledRuleAction> automatedAction = automationCandidates.isEmpty()
                ? Optional.empty()
                : Objects.requireNonNull(
                        provider.automatedAction(state, List.copyOf(automationCandidates)),
                        "provider returned null automated action");
        automatedAction.ifPresent(action -> validateAutomatedAction(action, automationCandidates));
        Optional<ScheduledRuleAction> scheduledAction =
                selectEarlier(systemAction, automatedAction);
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

    private PrivateRuleView validatedPrivateView(
            RuleState state, long revision, PlayerId player) {
        PrivateRuleView privateView = Objects.requireNonNull(
                provider.privateView(state, player, revision),
                "provider returned null private view");
        if (!privateView.viewer().equals(player) || privateView.stateRevision() != revision) {
            throw new IllegalStateException("Provider returned an unauthorized private view");
        }
        return privateView;
    }

    private List<LegalAction> validatedLegalActions(RuleState state, PlayerId player) {
        List<LegalAction> provided = Objects.requireNonNull(
                provider.legalActions(state, player), "provider returned null legal actions");
        if (provided.size() > limits.maxLegalActionsPerPlayer()) {
            throw new IllegalStateException("Provider exceeded legal-action limit");
        }
        List<LegalAction> actions = List.copyOf(provided);
        long distinctKeys = actions.stream().map(LegalAction::key).distinct().count();
        if (distinctKeys != actions.size()) {
            throw new IllegalStateException("Provider emitted duplicate legal-action keys");
        }
        return actions;
    }

    private RuleStateSnapshot validatedSnapshot(
            RuleState state, long sequence, String expectedHash) {
        RuleStateSnapshot snapshot = Objects.requireNonNull(
                provider.snapshot(state, sequence), "provider returned null snapshot");
        if (snapshot.sequence() != sequence || !snapshot.sha256().equals(expectedHash)) {
            throw new IllegalStateException("Provider snapshot provenance differs from rule state");
        }
        return snapshot;
    }

    private static String checkedHash(String hash) {
        if (hash == null || !SHA256.matcher(hash).matches()) {
            throw new IllegalStateException("Provider state hash is not lowercase SHA-256");
        }
        return hash;
    }

    private static void validateAutomatedAction(
            ScheduledRuleAction action, List<AutomatedPlayerActions> candidates) {
        AutomatedPlayerActions actor = candidates.stream()
                .filter(candidate -> candidate.actor().equals(action.actor()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Provider automated an uncontrolled actor"));
        if (actor.legalActions().stream()
                .noneMatch(legal -> legal.action().equals(action.action()))) {
            throw new IllegalStateException("Provider automated an action that is not legal");
        }
    }

    private static Optional<ScheduledRuleAction> selectEarlier(
            Optional<ScheduledRuleAction> system,
            Optional<ScheduledRuleAction> automated) {
        if (system.isEmpty()) {
            return automated;
        }
        if (automated.isEmpty()) {
            return system;
        }
        return automated.orElseThrow().delay().compareTo(system.orElseThrow().delay()) <= 0
                ? automated
                : system;
    }
}
