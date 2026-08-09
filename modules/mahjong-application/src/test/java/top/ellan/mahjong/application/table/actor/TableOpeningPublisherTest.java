package top.ellan.mahjong.application.table.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.application.opening.TableOpeningBatch;
import top.ellan.mahjong.domain.match.CompetitionRef;
import top.ellan.mahjong.domain.match.MatchBinding;
import top.ellan.mahjong.domain.match.MatchId;
import top.ellan.mahjong.domain.table.ParticipantRole;
import top.ellan.mahjong.domain.table.TableAggregate;
import top.ellan.mahjong.domain.table.TableId;
import top.ellan.mahjong.domain.table.TableLifecycle;
import top.ellan.mahjong.domain.table.TableParticipant;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.ProfileId;
import top.ellan.mahjong.spi.RuleDiceRoll;
import top.ellan.mahjong.spi.RuleId;
import top.ellan.mahjong.spi.RuleOpeningPresentation;
import top.ellan.mahjong.spi.RulePackRef;
import top.ellan.mahjong.spi.SeatId;

class TableOpeningPublisherTest {
    private static final RuleId RULE_ID = new RuleId("mcr");

    @Test
    void publishesInitialAndEachChangedHandExactlyOnce() {
        ArrayList<TableOpeningBatch> batches = new ArrayList<>();
        TableOpeningPublisher publisher =
                new TableOpeningPublisher(batches::add, aggregate(), RULE_ID, true);
        RuleOpeningPresentation first = opening(1, 2, 5);
        RuleOpeningPresentation second = opening(2, 3, 4);

        publisher.publishIfChanged(0, Optional.of(first));
        publisher.publishIfChanged(0, Optional.of(first));
        publisher.publishIfChanged(1, Optional.of(second));
        publisher.publishIfChanged(1, Optional.of(second));

        assertEquals(List.of(first, second), batches.stream()
                .map(TableOpeningBatch::opening)
                .toList());
    }

    @Test
    void recoverySuppressesOnlyTheRestoredOpening() {
        ArrayList<TableOpeningBatch> batches = new ArrayList<>();
        TableOpeningPublisher publisher =
                new TableOpeningPublisher(batches::add, aggregate(), RULE_ID, false);

        publisher.publishIfChanged(17, Optional.of(opening(4, 1, 6)));
        publisher.publishIfChanged(18, Optional.of(opening(5, 2, 3)));

        assertEquals(1, batches.size());
        assertEquals(5, batches.getFirst().opening().handSequence());
    }

    private static RuleOpeningPresentation opening(long hand, int first, int second) {
        return new RuleOpeningPresentation(
                hand,
                List.of(new RuleDiceRoll(List.of(first, second))),
                new SeatId(0),
                first + second);
    }

    private static TableAggregate aggregate() {
        PlayerId player = new PlayerId(UUID.randomUUID());
        MatchBinding binding = new MatchBinding(
                MatchId.random(),
                new RulePackRef(RULE_ID, "1.0.0", "0".repeat(64), 1),
                new ProfileId("green-book"),
                "0".repeat(64),
                Instant.parse("2026-08-09T00:00:00Z"));
        return new TableAggregate(
                TableId.random(),
                0,
                TableLifecycle.ACTIVE,
                List.of(new TableParticipant(
                        player, ParticipantRole.PLAYER, Optional.of(new SeatId(0)))),
                Optional.of(binding),
                CompetitionRef.none());
    }
}
