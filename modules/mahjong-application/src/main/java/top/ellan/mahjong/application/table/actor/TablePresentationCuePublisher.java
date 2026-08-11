package top.ellan.mahjong.application.table.actor;

import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.application.feedback.TableCueBatch;
import top.ellan.mahjong.application.feedback.TablePresentationCuePort;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.RulePresentationCue;

/** Failure-isolated one-shot publisher; scene refreshes and event replay never call this path. */
final class TablePresentationCuePublisher {
    private final TablePresentationCuePort port;
    private final top.ellan.mahjong.domain.table.TableId tableId;
    private final RulePackRef rulePack;
    private final List<PlayerId> audience;

    TablePresentationCuePublisher(
            TablePresentationCuePort port, TableAggregate aggregate, RulePackRef rulePack) {
        this.port = Objects.requireNonNull(port, "port");
        Objects.requireNonNull(aggregate, "aggregate");
        tableId = aggregate.tableId();
        this.rulePack = Objects.requireNonNull(rulePack, "rulePack");
        audience = aggregate.participants().stream()
                .map(TableParticipant::playerId)
                .toList();
    }

    void publish(long revision, List<RulePresentationCue> cues) {
        if (cues.isEmpty()) {
            return;
        }
        try {
            port.publish(new TableCueBatch(tableId, rulePack, revision, audience, cues));
        } catch (RuntimeException ignored) {
            // Transient feedback is never allowed to roll back or block committed rule state.
        }
    }
}
