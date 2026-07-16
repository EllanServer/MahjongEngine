package com.xbaimiao.invsync.api.addon;

public final class InvSyncAddonManager {
    public static InvSyncAddon registered;
    public static RuntimeException failure;

    private InvSyncAddonManager() {
    }

    public static void register(InvSyncAddon addon) {
        if (failure != null) {
            throw failure;
        }
        registered = addon;
    }
}
