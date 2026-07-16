package top.ellan.mahjong.compat.protection;

import java.util.Collection;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.momirealms.antigrieflib.AntiGriefLib;
import net.momirealms.antigrieflib.Flag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Applies the installed land-protection plugins to Mahjong table footprints. */
public final class ProtectionService {
    private final ProtectionTester tester;
    private final Logger logger;

    /**
     * Discovers supported protection plugins through AntiGriefLib.
     *
     * <p>AntiGriefLib has no shutdown lifecycle, so this service needs no close method.
     */
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

    public boolean canPlaceFootprint(Player player, Collection<Location> footprint) {
        return canModifyFootprint(player, footprint, Flag.PLACE, "place");
    }

    public boolean canBreakFootprint(Player player, Collection<Location> footprint) {
        return canModifyFootprint(player, footprint, Flag.BREAK, "break");
    }

    private boolean canModifyFootprint(
            Player player, Collection<Location> footprint, Flag<Location> flag, String operation) {
        if (player == null || footprint == null) {
            logger.warning(
                    "Invalid " + operation + " protection request; denying the operation.");
            return false;
        }
        for (Location location : footprint) {
            if (location == null) {
                logger.warning(
                        "Null location in "
                                + operation
                                + " footprint; denying the operation.");
                return false;
            }
            try {
                if (!tester.test(player, flag, location)) {
                    return false;
                }
            } catch (Exception | LinkageError exception) {
                logger.log(
                        Level.SEVERE,
                        "Protection provider failed while checking "
                                + operation
                                + " footprint; denying the operation.",
                        exception);
                return false;
            }
        }
        return true;
    }

    private static Bootstrap bootstrap(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Logger logger = Objects.requireNonNull(plugin.getLogger(), "plugin logger");
        try {
            AntiGriefLib antiGriefLib =
                    AntiGriefLib.builder(plugin)
                            .ignoreOP(false)
                            .silentLogs(false)
                            .bypassPermission("mahjongpaper.admin")
                            .suppressErrors(false)
                            .build();
            return new Bootstrap(antiGriefLib::test, logger);
        } catch (RuntimeException | LinkageError exception) {
            logger.log(
                    Level.SEVERE,
                    "AntiGriefLib initialization failed; all table footprint changes will be denied.",
                    exception);
            return new Bootstrap((player, flag, location) -> false, logger);
        }
    }

    @FunctionalInterface
    interface ProtectionTester {
        boolean test(Player player, Flag<Location> flag, Location location) throws Exception;
    }

    private record Bootstrap(ProtectionTester tester, Logger logger) {}
}
