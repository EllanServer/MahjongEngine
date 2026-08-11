package top.ellan.mahjong.application.automation;

import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.spi.PlayerId;

/** Bounded ingress for enabling or disabling trustee control on one active table. */
public interface TableAutomationEndpoint {
    CompletionStage<TableActionResult> setAutomated(PlayerId playerId, boolean enabled);
}
