package top.ellan.mahjong.runtime.resources;

import java.util.Objects;

/** Platform-neutral sound key and playback parameters supplied by a rule resource pack. */
public record RuleSoundProfile(String key, float volume, float pitch) {
    public RuleSoundProfile {
        key = Objects.requireNonNull(key, "key").trim();
        if (!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("sound key must be namespaced");
        }
        if (!Float.isFinite(volume) || volume < 0.0F || volume > 4.0F) {
            throw new IllegalArgumentException("sound volume must be between 0 and 4");
        }
        if (!Float.isFinite(pitch) || pitch < 0.5F || pitch > 2.0F) {
            throw new IllegalArgumentException("sound pitch must be between 0.5 and 2");
        }
    }
}
