package top.ellan.mahjong.application;

import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** Core-owned token issuer; rule packs never mint authority. */
@FunctionalInterface
public interface ActionTokenIssuer {
    ActionToken issue(PlayerId actor, long revision);
}
