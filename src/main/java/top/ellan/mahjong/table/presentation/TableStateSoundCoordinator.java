package top.ellan.mahjong.table.presentation;

import top.ellan.mahjong.presentation.TableFeedbackPolicy;
import top.ellan.mahjong.model.MahjongVariant;
import top.ellan.mahjong.riichi.ReactionResponse;
import top.ellan.mahjong.riichi.ReactionType;
import top.ellan.mahjong.table.core.TableSessionContext;
import top.ellan.mahjong.table.core.round.TableRoundController;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

public final class TableStateSoundCoordinator {
    private static final String NAMESPACE = "mahjongcraft";

    private static String sound(String name) {
        return NAMESPACE + ":" + name;
    }

    private final TableSessionContext session;
    private final TableFeedbackPolicy.DeliveryGate deliveryGate = new TableFeedbackPolicy.DeliveryGate();
    private int lastRemainingWallCount = -1;

    public TableStateSoundCoordinator(TableSessionContext session) {
        this.session = session;
    }

    public void syncStateSounds() {
        if (!this.session.hasRoundController()) {
            this.reset();
            return;
        }
        this.syncDrawSound();
        this.syncTurnSound();
        this.syncResolutionSound();
    }

    public void playReactionSound(ReactionResponse response) {
        switch (response.getType()) {
            case CHII  -> this.broadcastSound(this.variantSound("reaction_chi"), 0.8F, 1.1F);
            case PON   -> this.broadcastSound(this.variantSound("reaction_pon"), 0.8F, 1.1F);
            case MINKAN-> this.broadcastSound(this.variantSound("reaction_kan"), 0.8F, 1.1F);
            default -> { /* no custom sound for other reactions */ }
        }
    }

    public void playRoundStartSound() {
        this.broadcastSound(this.variantSound("tile_shuffle"), 0.9F, 1.2F);
    }

    public void playDiscardSound() {
        this.broadcastSound(this.variantSound("tile_discard"), 0.75F, 1.05F);
    }

    public void playRiichiSound() {
        this.broadcastSound(sound("riichi"), 0.8F, 1.25F);
    }

    public void reset() {
        this.deliveryGate.clear();
        this.lastRemainingWallCount = -1;
    }

    public void resetForRoundStart() {
        this.deliveryGate.shouldDeliver(
            null,
            TableFeedbackPolicy.Channel.SOUND,
            "resolution",
            Objects.toString(this.session.lastResolution(), "")
        );
        this.lastRemainingWallCount = -1;
    }

    private void syncDrawSound() {
        TableRoundController controller = this.session.roundControllerInternal();
        int remainingWallCount = controller == null || !controller.started()
            ? -1
            : controller.remainingWallCount();
        if (remainingWallCount >= 0
            && this.lastRemainingWallCount >= 0
            && remainingWallCount < this.lastRemainingWallCount
            && this.deliveryGate.shouldDeliver(
                null,
                TableFeedbackPolicy.Channel.SOUND,
                "draw",
                this.session.currentVariant() + ":" + remainingWallCount
            )) {
            this.broadcastSound(this.variantSound("tile_draw"), 0.65F, 1.05F);
        }
        this.lastRemainingWallCount = remainingWallCount;
    }

    private void syncTurnSound() {
        String turnFingerprint = this.session.isStarted() && this.session.currentSeat() != null
            ? this.session.currentSeat().name()
            : "";
        if (this.deliveryGate.shouldDeliver(
            null,
            TableFeedbackPolicy.Channel.SOUND,
            "turn",
            turnFingerprint
        )) {
            UUID currentPlayerId = this.session.playerAt(this.session.currentSeat());
            Player currentPlayer = currentPlayerId == null ? null : this.session.onlinePlayer(currentPlayerId);
            if (currentPlayer != null && !this.session.isBot(currentPlayerId)) {
                currentPlayer.playSound(currentPlayer.getLocation(), this.variantSound("turn_change"), 0.5F, 1.6F);
            }
        }
    }

    private void syncResolutionSound() {
        String resolutionFingerprint = Objects.toString(this.session.lastResolution(), "");
        if (this.deliveryGate.shouldDeliver(
            null,
            TableFeedbackPolicy.Channel.SOUND,
            "resolution",
            resolutionFingerprint
        )) {
            boolean isDraw = this.session.lastResolution().getDraw() != null;
            this.broadcastSound(this.variantSound(isDraw ? "round_draw" : "round_win"), 0.9F, 1.0F);
        }
    }

    private String variantSound(String baseName) {
        MahjongVariant variant = this.session.currentVariant();
        if (variant == MahjongVariant.GB) {
            return sound("gb_" + baseName);
        }
        if (variant == MahjongVariant.SICHUAN) {
            return sound("sichuan_" + baseName);
        }
        return sound(baseName);
    }

    private void broadcastSound(String soundKey, float volume, float pitch) {
        for (Player viewer : this.session.viewers()) {
            viewer.playSound(viewer.getLocation(), soundKey, volume, pitch);
        }
    }
}
