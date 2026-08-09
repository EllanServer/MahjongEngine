package top.ellan.mahjong.application.table.actor;

import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.application.opening.TableOpeningBatch;
import top.ellan.mahjong.application.opening.TableOpeningPresentationPort;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleOpeningPresentation;

/** Detects hand boundaries without replaying an opening when an actor restores a snapshot. */
final class TableOpeningPublisher {
    private final TableOpeningPresentationPort port;
    private final top.ellan.mahjong.domain.table.TableId tableId;
    private final RuleId ruleId;
    private final java.util.List<PlayerId> audience;
    private final boolean presentInitialOpening;
    private boolean initialized;
    private long lastHandSequence = -1;

    TableOpeningPublisher(
            TableOpeningPresentationPort port,
            TableAggregate aggregate,
            RuleId ruleId,
            boolean presentInitialOpening) {
        this.port = Objects.requireNonNull(port, "port");
        Objects.requireNonNull(aggregate, "aggregate");
        tableId = aggregate.tableId();
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        audience = aggregate.participants().stream()
                .filter(participant -> participant.role() != ParticipantRole.BOT)
                .map(TableParticipant::playerId)
                .toList();
        this.presentInitialOpening = presentInitialOpening;
    }

    void publishIfChanged(long revision, Optional<RuleOpeningPresentation> declared) {
        Objects.requireNonNull(declared, "declared");
        if (declared.isEmpty()) {
            initialized = true;
            return;
        }
        RuleOpeningPresentation opening = declared.orElseThrow();
        boolean changed = !initialized || opening.handSequence() != lastHandSequence;
        boolean shouldPresent = changed && (initialized || presentInitialOpening);
        initialized = true;
        lastHandSequence = opening.handSequence();
        if (!shouldPresent) {
            return;
        }
        try {
            port.present(new TableOpeningBatch(tableId, ruleId, revision, audience, opening));
        } catch (RuntimeException ignored) {
            // A cosmetic opening is never allowed to roll back or block rule state.
        }
    }
}
