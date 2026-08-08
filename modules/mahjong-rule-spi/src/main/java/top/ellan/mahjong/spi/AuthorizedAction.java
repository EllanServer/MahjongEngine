package top.ellan.mahjong.spi;

import java.util.Objects;

/** Core-issued token paired with the immutable action it authorizes. */
public record AuthorizedAction(ActionToken token, LegalAction legalAction) {
    public AuthorizedAction {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(legalAction, "legalAction");
    }
}
