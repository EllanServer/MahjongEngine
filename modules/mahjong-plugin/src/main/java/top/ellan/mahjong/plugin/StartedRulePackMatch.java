package top.ellan.mahjong.plugin;

import java.util.List;
import java.util.Objects;
import top.ellan.mahjong.application.table.actor.TableActor;
import top.ellan.mahjong.domain.MatchBinding;
import top.ellan.mahjong.domain.TableId;
import top.ellan.mahjong.domain.TableParticipant;

/** Live actor plus immutable durable identity. */
public record StartedRulePackMatch(
        MatchBinding binding,
        TableId tableId,
        List<TableParticipant> participants,
        TableActor actor) {
    public StartedRulePackMatch {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(tableId, "tableId");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        Objects.requireNonNull(actor, "actor");
    }
}
