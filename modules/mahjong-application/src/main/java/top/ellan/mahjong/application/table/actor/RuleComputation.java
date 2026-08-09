package top.ellan.mahjong.application.table.actor;

import java.util.Optional;
import top.ellan.mahjong.spi.RuleState;
import top.ellan.mahjong.spi.RuleStateSnapshot;
import top.ellan.mahjong.spi.RuleTransition;

/** Value returned from the bounded rule CPU pool to the table actor. */
record RuleComputation(
        RuleState state,
        RuleTransition transition,
        String beforeHash,
        String afterHash,
        Optional<RuleStateSnapshot> snapshot,
        RuleFrame frame) {}
