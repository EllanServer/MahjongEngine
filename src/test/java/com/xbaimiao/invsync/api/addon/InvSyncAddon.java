package com.xbaimiao.invsync.api.addon;

public interface InvSyncAddon {
    void onSync(TestSyncEvent event);

    default void onSave(TestSaveEvent event) {
    }

    default void onSave(TestSaveEvent event, TestSaveReason reason) {
    }
}
