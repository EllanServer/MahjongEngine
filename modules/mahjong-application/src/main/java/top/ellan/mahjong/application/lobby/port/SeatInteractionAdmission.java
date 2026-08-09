package top.ellan.mahjong.application.lobby.port;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.application.table.TableActionResult;

/** Immediate event-thread admission plus the actor's asynchronous authoritative result. */
public record SeatInteractionAdmission(
        boolean admitted, CompletionStage<TableActionResult> completion) {
    public SeatInteractionAdmission {
        Objects.requireNonNull(completion, "completion");
    }
}
