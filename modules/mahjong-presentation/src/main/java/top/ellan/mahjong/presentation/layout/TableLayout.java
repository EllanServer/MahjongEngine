package top.ellan.mahjong.presentation.layout;

import top.ellan.mahjong.spi.RuleTablePresentation;

/** Compiles a rule-declared physical table into a reusable constant-time lookup plan. */
public interface TableLayout {
    ResolvedTableLayout resolve(RuleTablePresentation tablePresentation);
}
