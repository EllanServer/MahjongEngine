package top.ellan.mahjong.application.automation;

import top.ellan.mahjong.spi.PlayerId;

/** Platform-neutral connection events used to start and stop disconnect takeover. */
public interface PlayerPresencePort {
    PlayerPresencePort NONE = new PlayerPresencePort() {
        @Override
        public void connected(PlayerId playerId) {}

        @Override
        public void disconnected(PlayerId playerId) {}
    };

    void connected(PlayerId playerId);

    void disconnected(PlayerId playerId);
}
