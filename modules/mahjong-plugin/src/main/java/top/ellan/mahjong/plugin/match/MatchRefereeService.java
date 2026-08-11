package top.ellan.mahjong.plugin.match;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import top.ellan.mahjong.application.table.TableActionResult;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.plugin.table.LiveTableDirectory;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.RuleAction;
import top.ellan.mahjong.spi.RuleId;

/** Routes a trusted ruling through the same bounded actor, rule pool and outbox as table actions. */
public final class MatchRefereeService {
    private static final RuleId SICHUAN = new RuleId("sichuan");

    private final LiveTableDirectory tables;

    public MatchRefereeService(LiveTableDirectory tables) {
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    public CompletionStage<TableActionResult> submit(
            TableId tableId, PlayerId authority, RuleAction action) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(action, "action");
        if (!action.type().startsWith("referee.")) {
            throw new IllegalArgumentException("Only typed referee actions are accepted");
        }
        StartedRulePackMatch match = tables.find(tableId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown live table"));
        if (!SICHUAN.equals(match.binding().rulePack().ruleId())) {
            throw new IllegalArgumentException("Competition rulings are available only for Sichuan");
        }
        long revision = match.actor().snapshot().revision();
        return match.actor().submitAuthority(authority, revision, action);
    }
}
