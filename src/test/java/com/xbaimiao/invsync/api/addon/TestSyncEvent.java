package com.xbaimiao.invsync.api.addon;

import java.util.Map;

public final class TestSyncEvent {
    private final TestPlayer player;
    private final Map<String, byte[]> data;

    public TestSyncEvent(TestPlayer player, Map<String, byte[]> data) {
        this.player = player;
        this.data = data;
    }

    public TestPlayer getPlayer() {
        return this.player;
    }

    public byte[] readData(String key) {
        return this.data.get(key);
    }
}
