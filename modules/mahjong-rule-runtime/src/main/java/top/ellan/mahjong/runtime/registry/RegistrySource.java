package top.ellan.mahjong.runtime.registry;

import java.io.IOException;

import top.ellan.mahjong.runtime.common.RulePackException;

/** Source of the signed registry envelope. Calls belong on an IO executor. */
@FunctionalInterface
public interface RegistrySource {
    byte[] fetch() throws IOException, InterruptedException, RulePackException;
}
