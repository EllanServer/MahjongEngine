package top.ellan.mahjong.application.table.actor;

import java.util.concurrent.CompletableFuture;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;

/** One bounded, trusted and revision-bound action accepted by a table inbox. */
record AuthorityActionEnvelope(
        long ingressOrder,
        PlayerId authority,
        long expectedRevision,
        RuleAction action,
        CompletableFuture<TableActionResult> response)
        implements TableIngress {}
