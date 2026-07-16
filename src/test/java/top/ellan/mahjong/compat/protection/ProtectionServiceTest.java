package top.ellan.mahjong.compat.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.momirealms.antigrieflib.Flag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ProtectionServiceTest {
    @Test
    void checksEveryPlaceFootprintLocationWithPlaceFlag() {
        Player player = mock(Player.class);
        Location first = new Location(null, 1.0, 2.0, 3.0);
        Location second = new Location(null, 4.0, 5.0, 6.0);
        List<Check> checks = new ArrayList<>();
        ProtectionService service =
                new ProtectionService(
                        (actualPlayer, flag, location) -> {
                            checks.add(new Check(actualPlayer, flag, location));
                            return true;
                        },
                        testLogger(new RecordingHandler()));

        assertTrue(service.canPlaceFootprint(player, List.of(first, second)));
        assertEquals(2, checks.size());
        assertSame(player, checks.get(0).player());
        assertSame(Flag.PLACE, checks.get(0).flag());
        assertSame(first, checks.get(0).location());
        assertSame(Flag.PLACE, checks.get(1).flag());
        assertSame(second, checks.get(1).location());
    }

    @Test
    void checksBreakFootprintWithBreakFlagAndDeniesRejectedPoint() {
        Player player = mock(Player.class);
        Location allowed = new Location(null, 1.0, 2.0, 3.0);
        Location denied = new Location(null, 4.0, 5.0, 6.0);
        List<Check> checks = new ArrayList<>();
        ProtectionService service =
                new ProtectionService(
                        (actualPlayer, flag, location) -> {
                            checks.add(new Check(actualPlayer, flag, location));
                            return location != denied;
                        },
                        testLogger(new RecordingHandler()));

        assertFalse(service.canBreakFootprint(player, List.of(allowed, denied)));
        assertEquals(2, checks.size());
        assertSame(Flag.BREAK, checks.get(0).flag());
        assertSame(Flag.BREAK, checks.get(1).flag());
    }

    @Test
    void failsClosedAndLogsWhenProviderThrows() {
        Player player = mock(Player.class);
        Location location = new Location(null, 1.0, 2.0, 3.0);
        RecordingHandler handler = new RecordingHandler();
        IllegalStateException failure = new IllegalStateException("provider unavailable");
        ProtectionService service =
                new ProtectionService(
                        (actualPlayer, flag, actualLocation) -> {
                            throw failure;
                        },
                        testLogger(handler));

        assertFalse(service.canPlaceFootprint(player, List.of(location)));
        assertEquals(1, handler.records.size());
        assertSame(failure, handler.records.getFirst().getThrown());
        assertTrue(handler.records.getFirst().getMessage().contains("denying"));
    }

    @Test
    void failsClosedAndLogsForNullFootprintPoint() {
        Player player = mock(Player.class);
        Location location = new Location(null, 1.0, 2.0, 3.0);
        RecordingHandler handler = new RecordingHandler();
        ProtectionService service =
                new ProtectionService(
                        (actualPlayer, flag, actualLocation) -> true, testLogger(handler));

        assertFalse(service.canBreakFootprint(player, Arrays.asList(location, null)));
        assertEquals(1, handler.records.size());
        assertTrue(handler.records.getFirst().getMessage().contains("Null location"));
    }

    @Test
    void allowsAnEmptyFootprintWithoutCallingProvider() {
        Player player = mock(Player.class);
        ProtectionService service =
                new ProtectionService(
                        (actualPlayer, flag, actualLocation) -> {
                            throw new AssertionError("provider should not be called");
                        },
                        testLogger(new RecordingHandler()));

        assertTrue(service.canPlaceFootprint(player, List.of()));
    }

    private static Logger testLogger(Handler handler) {
        Logger logger = Logger.getLogger(ProtectionServiceTest.class.getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        return logger;
    }

    private record Check(Player player, Flag<Location> flag, Location location) {}

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
