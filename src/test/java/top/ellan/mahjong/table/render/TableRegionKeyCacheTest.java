package top.ellan.mahjong.table.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.ellan.mahjong.model.SeatWind;

class TableRegionKeyCacheTest {
    private static final int EXPECTED_KEY_COUNT = 452;

    @Test
    void reusesEveryProductionRegionKeyWithoutChangingItsText() throws ReflectiveOperationException {
        TableRegionDisplayCoordinator coordinator = new TableRegionDisplayCoordinator(null, null);
        Method seatKey = method("seatRegionKey", String.class, SeatWind.class);
        Method handPrivateKey = method("handPrivateRegionKey", SeatWind.class, int.class);
        Method handPublicKey = method("handPublicRegionKey", SeatWind.class, int.class);
        Method discardKey = method("discardRegionKey", SeatWind.class, int.class);
        Method meldKey = method("meldRegionKey", SeatWind.class, int.class);
        Method wallKey = method("wallRegionKey", int.class);
        Set<String> keys = new HashSet<>(EXPECTED_KEY_COUNT);

        for (int index = 0; index < 136; index++) {
            this.assertCached(keys, wallKey, "wall-" + index, coordinator, index);
        }
        for (SeatWind wind : SeatWind.values()) {
            String suffix = ":" + wind.name();
            for (String region : new String[] {
                "visual", "labels", "sticks", "hand-public", "hand-private", "discards", "melds"
            }) {
                this.assertCached(keys, seatKey, region + suffix, coordinator, region, wind);
            }
            for (int index = 0; index < 14; index++) {
                this.assertCached(
                    keys,
                    handPublicKey,
                    "hand-public-" + index + suffix,
                    coordinator,
                    wind,
                    index
                );
                this.assertCached(
                    keys,
                    handPrivateKey,
                    "hand-private-" + index + suffix,
                    coordinator,
                    wind,
                    index
                );
            }
            for (int index = 0; index < 24; index++) {
                this.assertCached(keys, discardKey, "discards-" + index + suffix, coordinator, wind, index);
            }
            for (int index = 0; index < 20; index++) {
                this.assertCached(keys, meldKey, "melds-" + index + suffix, coordinator, wind, index);
            }
        }

        assertEquals(EXPECTED_KEY_COUNT, keys.size());
    }

    @Test
    void retainsLegacyFallbacksOutsideTheProductionKeyRanges() throws ReflectiveOperationException {
        TableRegionDisplayCoordinator coordinator = new TableRegionDisplayCoordinator(null, null);
        SeatWind wind = SeatWind.EAST;

        assertEquals(
            "viewer-overlay:EAST",
            invoke(method("seatRegionKey", String.class, SeatWind.class), coordinator, "viewer-overlay", wind)
        );
        assertEquals(
            "hand-private--1:EAST",
            invoke(method("handPrivateRegionKey", SeatWind.class, int.class), coordinator, wind, -1)
        );
        assertEquals(
            "hand-public-14:EAST",
            invoke(method("handPublicRegionKey", SeatWind.class, int.class), coordinator, wind, 14)
        );
        assertEquals(
            "discards-24:EAST",
            invoke(method("discardRegionKey", SeatWind.class, int.class), coordinator, wind, 24)
        );
        assertEquals(
            "melds-20:EAST",
            invoke(method("meldRegionKey", SeatWind.class, int.class), coordinator, wind, 20)
        );
        assertEquals("wall-136", invoke(method("wallRegionKey", int.class), coordinator, 136));
    }

    private void assertCached(
        Set<String> keys,
        Method method,
        String expected,
        TableRegionDisplayCoordinator coordinator,
        Object... arguments
    ) throws ReflectiveOperationException {
        String first = invoke(method, coordinator, arguments);
        String second = invoke(method, coordinator, arguments);
        assertEquals(expected, first);
        assertSame(first, second);
        keys.add(first);
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = TableRegionDisplayCoordinator.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static String invoke(Method method, TableRegionDisplayCoordinator coordinator, Object... arguments)
        throws ReflectiveOperationException {
        return (String) method.invoke(coordinator, arguments);
    }
}
