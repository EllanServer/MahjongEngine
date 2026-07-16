package top.ellan.mahjong.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.momirealms.sparrow.reflection.SReflection;
import org.junit.jupiter.api.Test;

final class ClientCameraBridgeMemberResolutionTest {
    @Test
    void connectionFieldIsResolvedByTypeAcrossSuperclasses() throws IllegalAccessException {
        TestConnection expected = new TestConnection();
        TestServerPlayer player = new TestServerPlayer(expected);

        Field field = ClientCameraBridge.findConnectionField(TestServerPlayer.class, TestConnection.class);

        assertNotNull(field);
        assertEquals("obfuscatedConnection", field.getName());
        assertSame(expected, field.get(player));
    }

    @Test
    void mappedConnectionNameRemainsAFallbackWhenConcreteTypeChanges() {
        Field field = ClientCameraBridge.findConnectionField(LegacyServerPlayer.class, TestConnection.class);

        assertNotNull(field);
        assertEquals("connection", field.getName());
    }

    @Test
    void packetSenderIsResolvedByPacketParameterAcrossSuperclasses() {
        Method method = ClientCameraBridge.findPacketSendMethod(TestConnection.class, TestPacket.class);

        assertNotNull(method);
        assertEquals("send", method.getName());
        assertEquals(TestPacket.class, method.getParameterTypes()[0]);
    }

    @Test
    void obfuscatedPacketSenderFallsBackToItsExactPacketSignature() {
        Method method = ClientCameraBridge.findPacketSendMethod(ObfuscatedConnection.class, TestPacket.class);

        assertNotNull(method);
        assertEquals("a", method.getName());
    }

    @Test
    void booleanPacketCheckInSubclassDoesNotHideVoidSenderInSuperclass() {
        Method method = ClientCameraBridge.findPacketSendMethod(
            LegacyCheckingConnection.class,
            TestPacket.class
        );

        assertNotNull(method);
        assertEquals("a", method.getName());
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    void privateFinalCameraIdCanBeInitializedThroughSparrowSetter() throws Throwable {
        Field field = TestCameraPacket.class.getDeclaredField("cameraEntityId");
        MethodHandle setter = ClientCameraBridge.unreflectFinalFieldSetter(field);
        TestCameraPacket packet = SReflection.allocateInstance(TestCameraPacket.class);

        setter.invoke(packet, 73);

        field.trySetAccessible();
        assertEquals(73, field.getInt(packet));
    }

    private interface TestPacket {
    }

    private static final class TestCameraPacket {
        @SuppressWarnings("unused")
        private final int cameraEntityId = -1;
    }

    private static class BaseConnection {
        public void send(TestPacket packet) {
        }
    }

    private static final class TestConnection extends BaseConnection {
    }

    private static final class ObfuscatedConnection {
        public void a(TestPacket packet) {
        }
    }

    private static class LegacyConnectionBase {
        public void a(TestPacket packet) {
        }
    }

    private static final class LegacyCheckingConnection extends LegacyConnectionBase {
        @SuppressWarnings("unused")
        public boolean shouldHandleMessage(TestPacket packet) {
            return true;
        }
    }

    private static class BaseServerPlayer {
        @SuppressWarnings("unused")
        private final TestConnection obfuscatedConnection;

        private BaseServerPlayer(TestConnection connection) {
            this.obfuscatedConnection = connection;
        }
    }

    private static final class TestServerPlayer extends BaseServerPlayer {
        private TestServerPlayer(TestConnection connection) {
            super(connection);
        }
    }

    private static final class LegacyServerPlayer {
        @SuppressWarnings("unused")
        private final ObfuscatedConnection connection = new ObfuscatedConnection();
    }
}
