package top.ellan.mahjong.application.table;

import java.util.Objects;

/** Result returned without ever blocking a Paper/CraftEngine event thread. */
public record TableActionResult(TableActionCode code, long revision, String reasonCode) {
    public TableActionResult {
        Objects.requireNonNull(code, "code");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }
}
