package top.ellan.mahjong.runtime;

import java.io.IOException;

/** Source of the signed registry envelope. Calls belong on an IO executor. */
@FunctionalInterface
public interface RegistrySource {
    byte[] fetch() throws IOException, InterruptedException, RulePackException;
}
