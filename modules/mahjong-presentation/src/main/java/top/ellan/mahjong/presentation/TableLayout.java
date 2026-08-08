package top.ellan.mahjong.presentation;

import java.util.Optional;
import top.ellan.mahjong.spi.RuleViewZone;
import top.ellan.mahjong.spi.SeatId;

/** Variant-neutral geometry policy. */
public interface TableLayout {
    SceneTransform tile(RuleViewZone zone, Optional<SeatId> owner, int index);

    SceneTransform interaction(int index);
}
