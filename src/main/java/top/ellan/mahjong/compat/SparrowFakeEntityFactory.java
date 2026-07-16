package top.ellan.mahjong.compat;

import java.util.Objects;
import net.momirealms.sparrow.heart.SparrowHeart;
import net.momirealms.sparrow.heart.feature.entity.armorstand.FakeArmorStand;
import net.momirealms.sparrow.heart.feature.entity.display.FakeItemDisplay;
import net.momirealms.sparrow.heart.feature.entity.display.FakeTextDisplay;
import org.bukkit.Location;

/**
 * Serializes fake-entity allocation across every MahjongPaper Sparrow feature.
 *
 * <p>Sparrow Heart 0.72 allocates IDs through a process-wide non-atomic counter. Keeping camera
 * and overlay creation behind the same lock prevents our Folia region tasks from racing that
 * counter until the upstream allocator becomes atomic.</p>
 */
public final class SparrowFakeEntityFactory {
    private static final Object CREATION_LOCK = new Object();

    private SparrowFakeEntityFactory() {
    }

    public static FakeItemDisplay createItemDisplay(SparrowHeart heart, Location location) {
        Objects.requireNonNull(heart, "heart");
        synchronized (CREATION_LOCK) {
            return heart.createFakeItemDisplay(location);
        }
    }

    public static FakeArmorStand createArmorStand(SparrowHeart heart, Location location) {
        Objects.requireNonNull(heart, "heart");
        synchronized (CREATION_LOCK) {
            return heart.createFakeArmorStand(location);
        }
    }

    public static FakeTextDisplay createTextDisplay(SparrowHeart heart, Location location) {
        Objects.requireNonNull(heart, "heart");
        synchronized (CREATION_LOCK) {
            return heart.createFakeTextDisplay(location);
        }
    }
}
