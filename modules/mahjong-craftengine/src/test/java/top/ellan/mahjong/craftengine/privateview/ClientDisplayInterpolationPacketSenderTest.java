package top.ellan.mahjong.craftengine.privateview;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class ClientDisplayInterpolationPacketSenderTest {
    @Test
    void resolvesOnlyTheExactNamedAccessor() {
        assertNotNull(ClientDisplayInterpolationPacketSender.findPositionRotationAccessor(
                ModernDisplay.class, DummyAccessor.class));
        assertNull(ClientDisplayInterpolationPacketSender.findPositionRotationAccessor(
                LegacyDisplay.class, DummyAccessor.class));
    }

    private static final class DummyAccessor {}

    private static final class ModernDisplay {
        @SuppressWarnings("unused")
        private static final DummyAccessor DATA_POS_ROT_INTERPOLATION_DURATION_ID =
                new DummyAccessor();
    }

    private static final class LegacyDisplay {
        @SuppressWarnings("unused")
        private static final DummyAccessor START_DELTA = new DummyAccessor();

        @SuppressWarnings("unused")
        private static final DummyAccessor TRANSFORMATION_DURATION = new DummyAccessor();

        @SuppressWarnings("unused")
        private static final DummyAccessor TRANSLATION = new DummyAccessor();
    }
}
