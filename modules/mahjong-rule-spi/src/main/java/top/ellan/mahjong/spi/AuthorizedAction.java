package top.ellan.mahjong.spi;

import java.util.Objects;

/**
 * Core-issued token paired with the immutable action it authorizes.
 *
 * @param token core-issued authorization token
 * @param legalAction immutable legal action authorized by the token
 */
public record AuthorizedAction(ActionToken token, LegalAction legalAction) {
    /**
     * Creates an immutable token and action pair.
     *
     * @param token core-issued authorization token
     * @param legalAction immutable action authorized by the token
     */
    public AuthorizedAction {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(legalAction, "legalAction");
    }
}
