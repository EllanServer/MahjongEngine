package top.ellan.mahjong.application.opening;

/** Non-blocking transient effects synchronized to the CE-owned opening animation. */
public interface TableOpeningEffectPort {
    TableOpeningEffectPort NONE = new TableOpeningEffectPort() {
        @Override
        public void rollStarted(TableOpeningBatch batch, int rollIndex) {}

        @Override
        public void wallOpened(TableOpeningBatch batch) {}
    };

    void rollStarted(TableOpeningBatch batch, int rollIndex);

    void wallOpened(TableOpeningBatch batch);
}
