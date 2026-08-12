package top.ellan.mahjong.application.table.actor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.ellan.mahjong.application.concurrent.TaskScheduler;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/** Actor-owned automation and human-deadline state for one table's fixed participant roster. */
final class TableActorAutomationController {
    private final TableAutomationRoster roster;
    private final HumanDecisionDeadlineController deadlines;
    private boolean refreshPending;
    private PendingSubmission pendingSubmission;

    TableActorAutomationController(
            List<TableParticipant> participants,
            TaskScheduler scheduler,
            TableActorInbox inbox,
            Runnable wakeActor) {
        roster = new TableAutomationRoster(participants);
        deadlines = new HumanDecisionDeadlineController(scheduler, inbox, wakeActor);
    }

    boolean isAutomated(PlayerId playerId) {
        return roster.isAutomated(Objects.requireNonNull(playerId, "playerId"));
    }

    List<PlayerId> automatedPlayers() {
        return roster.automatedPlayers();
    }

    boolean acceptTimeout(
            HumanDecisionTimeoutTrigger trigger,
            TableActorStateMachine state,
            boolean closed,
            boolean ruleInFlight) {
        if (closed
                || trigger.expectedRevision() != state.revision()
                || !state.lifecycle().acceptsRuleActions()
                || ruleInFlight) {
            return false;
        }
        boolean changed = false;
        for (HumanDecisionTimeout timeout : deadlines.accept(trigger, state.revision())) {
            changed |= roster.enableOneShot(timeout.actor());
        }
        return changed;
    }

    boolean control(
            AutomationControlEnvelope envelope,
            TableActorStateMachine state,
            boolean closed,
            boolean ruleInFlight) {
        if (closed) {
            envelope.response().complete(state.result(TableActionCode.TABLE_CLOSED, "closed"));
            return false;
        }
        if (!state.lifecycle().acceptsRuleActions()) {
            envelope.response().complete(
                    state.result(TableActionCode.TABLE_BLOCKED, "table-not-active"));
            return false;
        }
        TableAutomationRoster.Update update = roster.update(
                envelope.playerId(), envelope.enabled());
        if (!update.accepted()) {
            envelope.response().complete(
                    state.result(TableActionCode.REJECTED_BY_RULES, update.reasonCode()));
            return false;
        }
        boolean compute = false;
        if (update.changed()) {
            state.pauseScheduledAction();
            deadlines.pause();
            if (ruleInFlight) {
                refreshPending = true;
            } else {
                compute = true;
            }
        }
        envelope.response().complete(
                state.result(TableActionCode.ACCEPTED_MEMORY, update.reasonCode()));
        return compute;
    }

    void beforeTransition(
            PlayerId actor,
            RuleAction action,
            boolean playerSubmission,
            Optional<ScheduledActionTrigger> scheduledTrigger) {
        deadlines.pause();
        pendingSubmission = new PendingSubmission(
                actor,
                action,
                playerSubmission
                        ? SubmissionSource.PLAYER
                        : scheduledTrigger.isPresent()
                                ? SubmissionSource.SCHEDULED
                                : SubmissionSource.AUTHORITY,
                scheduledTrigger.map(value -> value.scheduledAction().reasonCode()).orElse(""));
    }

    void transitionRejected() {
        pendingSubmission = null;
    }

    void completionDiscarded() {
        pendingSubmission = null;
    }

    /** Returns true when the actor must request a fresh automation-aware frame. */
    boolean afterCompletion(RuleTaskCompletion completion, TableActorStateMachine state) {
        PendingSubmission submitted = pendingSubmission;
        pendingSubmission = null;
        boolean refresh = acceptedSubmission(completion, submitted);
        if (refreshPending) {
            refreshPending = false;
            refresh = true;
        }
        if (refresh) {
            if (state.lifecycle().acceptsRuleActions()) {
                state.pauseScheduledAction();
                deadlines.pause();
                return true;
            }
            return false;
        }
        if (!state.lifecycle().acceptsRuleActions()) {
            deadlines.clear();
            return false;
        }
        RuleComputation computed = completion.computed();
        if (computed != null && computed.frame() != null) {
            deadlines.install(
                            computed.frame(),
                            state.revision(),
                            Set.copyOf(roster.automatedPlayers()),
                            computed.frame().scheduledAction().isEmpty())
                    .ifPresent(state::block);
        }
        return false;
    }

    void clear() {
        deadlines.clear();
        pendingSubmission = null;
        refreshPending = false;
    }

    private boolean acceptedSubmission(
            RuleTaskCompletion completion, PendingSubmission submitted) {
        if (submitted == null
                || completion.computed() == null
                || completion.computed().transition() == null
                || !completion.computed().transition().accepted()) {
            return false;
        }
        if (submitted.source() == SubmissionSource.PLAYER) {
            deadlines.acceptedManual(submitted.actor(), submitted.action());
            return roster.completeOneShot(submitted.actor());
        }
        return submitted.source() == SubmissionSource.SCHEDULED
                && submitted.reasonCode().startsWith("automation.")
                && roster.completeOneShot(submitted.actor());
    }

    private enum SubmissionSource {
        PLAYER,
        AUTHORITY,
        SCHEDULED
    }

    private record PendingSubmission(
            PlayerId actor,
            RuleAction action,
            SubmissionSource source,
            String reasonCode) {
        private PendingSubmission {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(source, "source");
            reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }
}
