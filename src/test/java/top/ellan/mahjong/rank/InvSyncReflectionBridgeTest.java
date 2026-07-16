package top.ellan.mahjong.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xbaimiao.invsync.api.addon.InvSyncAddon;
import com.xbaimiao.invsync.api.addon.InvSyncAddonManager;
import com.xbaimiao.invsync.api.addon.TestPlayer;
import com.xbaimiao.invsync.api.addon.TestSaveEvent;
import com.xbaimiao.invsync.api.addon.TestSaveReason;
import com.xbaimiao.invsync.api.addon.TestSyncEvent;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InvSyncReflectionBridgeTest {
    @AfterEach
    void resetManager() {
        InvSyncAddonManager.registered = null;
        InvSyncAddonManager.failure = null;
    }

    @Test
    void registersProxyAndForwardsDocumentedEvents() throws Exception {
        AtomicInteger syncCalls = new AtomicInteger();
        AtomicInteger saveCalls = new AtomicInteger();
        InvSyncReflectionBridge.register(this.getClass().getClassLoader(), new InvSyncReflectionBridge.EventHandler() {
            @Override
            public void onSync(Object event) {
                syncCalls.incrementAndGet();
            }

            @Override
            public void onSave(Object event, Object reason) {
                saveCalls.incrementAndGet();
                assertEquals(TestSaveReason.AUTO_SAVE, reason);
            }
        });

        InvSyncAddon addon = InvSyncAddonManager.registered;
        assertNotNull(addon);
        TestPlayer player = new TestPlayer(UUID.randomUUID(), "Player");
        addon.onSync(new TestSyncEvent(player, new HashMap<>()));
        addon.onSave(new TestSaveEvent(player, new HashMap<>()), TestSaveReason.AUTO_SAVE);
        assertEquals(1, syncCalls.get());
        assertEquals(1, saveCalls.get());
    }

    @Test
    void reportsRegistrationFailureSoBootstrapCanFallback() {
        InvSyncAddonManager.failure = new IllegalStateException("rejected");
        assertThrows(
            ReflectiveOperationException.class,
            () -> InvSyncReflectionBridge.register(this.getClass().getClassLoader(), new NoOpHandler())
        );
    }

    private static final class NoOpHandler implements InvSyncReflectionBridge.EventHandler {
        @Override
        public void onSync(Object event) {
        }

        @Override
        public void onSave(Object event, Object reason) {
        }
    }
}
