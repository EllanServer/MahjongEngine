package com.xbaimiao.invsync.api.addon;

import java.util.UUID;

public record TestPlayer(UUID uniqueId, String name) {
    public UUID getUniqueId() {
        return this.uniqueId;
    }

    public String getName() {
        return this.name;
    }
}
