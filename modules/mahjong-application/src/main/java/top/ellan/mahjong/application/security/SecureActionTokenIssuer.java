package top.ellan.mahjong.application.security;

import java.util.UUID;
import top.ellan.mahjong.spi.ActionToken;
import top.ellan.mahjong.spi.PlayerId;

/** UUID-based production token issuer. */
public final class SecureActionTokenIssuer implements ActionTokenIssuer {
    @Override
    public ActionToken issue(PlayerId actor, long revision) {
        return new ActionToken(UUID.randomUUID(), actor, revision);
    }
}
