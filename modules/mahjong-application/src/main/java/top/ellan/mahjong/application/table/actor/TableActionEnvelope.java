package top.ellan.mahjong.application.table.actor;

import java.util.concurrent.CompletableFuture;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** One bounded external action accepted by a table inbox. */
record TableActionEnvelope(
        PlayerId actor,
        ActionToken token,
        CompletableFuture<TableActionResult> response) {}
