package top.ellan.mahjong.domain;

/** Selected once at a new-match boundary and never changed during that match. */
public enum RuleMigrationMode {
    LEGACY,
    SHADOW,
    RULE_PACK
}
