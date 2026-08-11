package top.ellan.mahjong.presentation.projection.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.spi.PlayerId;
import top.ellan.mahjong.spi.PrivateRuleView;
import top.ellan.mahjong.spi.RuleTilePresentation;
import top.ellan.mahjong.spi.RuleViewTile;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.SeatId;
import top.ellan.mahjong.spi.TileInstanceId;
import top.ellan.mahjong.spi.TileVisualId;

class ViewerZoneCountsTest {
    private static final PlayerId FIRST =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
    private static final PlayerId SECOND =
            new PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"));

    @Test
    void oneViewerIsCountedOnceAndSharedByEveryProjector() {
        ViewerZoneCounts counts = new ViewerZoneCounts();
        PrivateRuleView view = view(FIRST, 0, 3);

        ZoneTileCounts first = counts.of(FIRST, view);
        ZoneTileCounts second = counts.of(FIRST, view);

        // The private and interaction projectors must observe the very same instance, otherwise a
        // hand action could be placed against a different tile count than the tile it acts on.
        assertSame(first, second);
        assertEquals(3, first.count(view.tiles().getFirst()));
    }

    @Test
    void separateViewersKeepIndependentCounts() {
        ViewerZoneCounts counts = new ViewerZoneCounts();
        PrivateRuleView firstView = view(FIRST, 0, 3);
        PrivateRuleView secondView = view(SECOND, 1, 5);

        ZoneTileCounts first = counts.of(FIRST, firstView);
        ZoneTileCounts second = counts.of(SECOND, secondView);

        assertNotSame(first, second);
        assertEquals(3, first.count(firstView.tiles().getFirst()));
        assertEquals(5, second.count(secondView.tiles().getFirst()));
    }

    private static PrivateRuleView view(PlayerId viewer, int seat, int handSize) {
        SeatId seatId = new SeatId(seat);
        List<RuleViewTile> tiles = new java.util.ArrayList<>(handSize);
        for (int index = 0; index < handSize; index++) {
            tiles.add(
                    new RuleViewTile(
                            new TileInstanceId(seat * 100L + index),
                            new TileVisualId("riichi:tile/m" + (index + 1)),
                            Optional.of(seatId),
                            RuleViewZone.HAND,
                            index,
                            true,
                            RuleTilePresentation.natural(index)));
        }
        return new PrivateRuleView(1, viewer, seatId, List.copyOf(tiles), Map.of());
    }
}
