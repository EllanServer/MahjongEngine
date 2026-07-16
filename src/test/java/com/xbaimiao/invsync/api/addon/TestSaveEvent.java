package com.xbaimiao.invsync.api.addon;

import java.util.Map;

public final class TestSaveEvent {
    private final TestPlayer player;
    private final Map<String, byte[]> data;

    public TestSaveEvent(TestPlayer player, Map<String, byte[]> data) {
        this.player = player;
        this.data = data;
    }

    public TestPlayer getPlayer() {
        return this.player;
    }

    public void putData(String key, byte[] value) {
        this.data.put(key, value);
    }
}
