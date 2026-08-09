package top.ellan.mahjong.application.table;

import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/**
 * Trusted, revision-bound ingress for platform-authorized referee and operator actions.
 *
 * <p>This endpoint deliberately bypasses player action tokens, but never bypasses the owning
 * table actor, the fair rule pool, rule-pack validation, or the persistence outbox.</p>
 */
public interface TableAuthorityActionEndpoint {
    CompletionStage<TableActionResult> submitAuthority(
            PlayerId authority, long expectedRevision, RuleAction action);
}
