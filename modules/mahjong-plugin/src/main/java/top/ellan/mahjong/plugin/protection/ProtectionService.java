package top.ellan.mahjong.plugin.protection;

import java.util.Collection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.antigrieflib.AntiGriefLib;
import net.momirealms.antigrieflib.Flag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Fail-closed land-protection boundary backed by AntiGriefLib provider adapters. */
public final class ProtectionService {
    private final ProtectionTester tester;
    private final Logger logger;

    public ProtectionService(JavaPlugin plugin) {
        this(bootstrap(plugin));
    }

    ProtectionService(ProtectionTester tester, Logger logger) {
        this.tester = Objects.requireNonNull(tester, "tester");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private ProtectionService(Bootstrap bootstrap) {
        this(bootstrap.tester(), bootstrap.logger());
    }

    public boolean canPlace(Player player, Collection<Location> footprint) {
        return testFootprint(player, footprint, Flag.PLACE, "place");
    }

    public boolean canBreak(Player player, Collection<Location> footprint) {
        return testFootprint(player, footprint, Flag.BREAK, "break");
    }

    private boolean testFootprint(
            Player player,
            Collection<Location> footprint,
            Flag<Location> flag,
            String operation) {
        if (player == null || footprint == null || footprint.isEmpty()) {
            logger.warning("Invalid " + operation + " protection footprint; denying operation");
            return false;
        }
        for (Location location : footprint) {
            if (location == null) {
                logger.warning("Null point in " + operation + " protection footprint; denying operation");
                return false;
            }
            try {
                if (!tester.test(player, flag, location)) {
                    return false;
                }
            } catch (Exception | LinkageError failure) {
                logger.log(
                        Level.SEVERE,
                        "Protection provider failed during " + operation + "; denying operation",
                        failure);
                return false;
            }
        }
        return true;
    }

    private static Bootstrap bootstrap(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Logger logger = plugin.getLogger();
        try {
            AntiGriefLib antiGrief =
                    AntiGriefLib.builder(plugin)
                            .ignoreOP(false)
                            .silentLogs(false)
                            .bypassPermission("mahjongpaper.admin")
                            .suppressErrors(false)
                            .build();
            return new Bootstrap(antiGrief::test, logger);
        } catch (RuntimeException | LinkageError failure) {
            logger.log(
                    Level.SEVERE,
                    "AntiGriefLib initialization failed; table footprint changes are denied",
                    failure);
            return new Bootstrap((player, flag, location) -> false, logger);
        }
    }

    @FunctionalInterface
    interface ProtectionTester {
        boolean test(Player player, Flag<Location> flag, Location location) throws Exception;
    }

    private record Bootstrap(ProtectionTester tester, Logger logger) {}
}
