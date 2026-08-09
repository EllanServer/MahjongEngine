package top.ellan.mahjong.application.interaction;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.application.table.TableActionCode;
import top.ellan.mahjong.application.table.TableActionResult;

/** Allocation-minimal construction helpers shared by interaction components. */
final class InteractionResults {
    private InteractionResults() {}

    static CompletionStage<TableActionResult> completed(
            TableActionCode code, long revision, String reason) {
        return CompletableFuture.completedFuture(result(code, revision, reason));
    }

    static TableActionResult result(
            TableActionCode code, long revision, String reason) {
        return new TableActionResult(code, revision, reason);
    }
}
