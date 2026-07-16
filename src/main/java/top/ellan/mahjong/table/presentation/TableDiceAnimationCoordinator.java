package top.ellan.mahjong.table.presentation;

import top.ellan.mahjong.compat.PaperCompatibility;
import top.ellan.mahjong.render.display.TextDisplayBackgrounds;
import top.ellan.mahjong.runtime.PluginTask;
import top.ellan.mahjong.table.core.TableSessionContext;
import top.ellan.mahjong.riichi.model.OpeningDiceRoll;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class TableDiceAnimationCoordinator {
    private static final long ROLL_TICKS = 20L;
    private static final long REVEAL_TICKS = 12L;
    private static final float DIE_SCALE = 0.62F;
    private static final double DIE_Y = 0.68D;
    private static final double DIE_X_OFFSET = 0.16D;
    private static final double RESULT_LABEL_Y = 1.18D;
    private static final Color RESULT_LABEL_BACKGROUND = Color.fromARGB(104, 20, 20, 24);
    private static final Map<Integer, ItemStack> DICE_ITEM_CACHE = new ConcurrentHashMap<>();

    private final TableSessionContext session;
    private final Vector3f dieScaleVector = new Vector3f(DIE_SCALE, DIE_SCALE, DIE_SCALE);
    private final Vector3f zeroTranslation = new Vector3f();
    private final Quaternionf identityRotation = new Quaternionf();
    private PluginTask animationTask;
    private ItemDisplay firstDie;
    private ItemDisplay secondDie;
    private TextDisplay resultLabel;
    private OpeningDiceRoll roll;
    private Runnable onComplete;
    private Location center;
    private World world;
    private List<Player> audience = List.of();
    private long tick;

    public TableDiceAnimationCoordinator(TableSessionContext session) {
        this.session = session;
    }

    public boolean active() {
        return this.roll != null;
    }

    public boolean shouldAnimate() {
        return !this.session.viewers().isEmpty() && this.session.center().getWorld() != null;
    }

    public void start(OpeningDiceRoll roll, Runnable onComplete) {
        this.clear();
        if (roll == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        List<Player> viewers = this.onlineAudienceSnapshot();
        Location tableCenter = this.session.center();
        World tableWorld = tableCenter.getWorld();
        if (viewers.isEmpty() || tableWorld == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        this.roll = roll;
        this.onComplete = onComplete;
        this.center = tableCenter;
        this.world = tableWorld;
        this.audience = viewers;
        this.tick = 0L;
        this.firstDie = this.spawnDie(this.baseLocation(-DIE_X_OFFSET), roll.firstDie());
        this.secondDie = this.spawnDie(this.baseLocation(DIE_X_OFFSET), roll.secondDie());
        this.broadcastSound(Sound.ENTITY_ITEM_PICKUP, 0.55F, 0.7F);
        this.animationTask = this.session.plugin().scheduler().runRegionTimer(this.center, this::advance, 1L, 1L);
    }

    public void clear() {
        if (this.animationTask != null) {
            this.animationTask.cancel();
            this.animationTask = null;
        }
        this.removeEntity(this.resultLabel);
        this.removeEntity(this.firstDie);
        this.removeEntity(this.secondDie);
        this.resultLabel = null;
        this.firstDie = null;
        this.secondDie = null;
        this.roll = null;
        this.onComplete = null;
        this.center = null;
        this.world = null;
        this.audience = List.of();
        this.tick = 0L;
    }

    private void advance() {
        this.tick++;
        if (this.roll == null || this.firstDie == null || this.secondDie == null) {
            this.finish();
            return;
        }
        if (this.tick <= ROLL_TICKS) {
            this.animateRollingDie(this.firstDie, -DIE_X_OFFSET, this.tick, 0.35D, 0.22D, 0.48F);
            this.animateRollingDie(this.secondDie, DIE_X_OFFSET, this.tick, -0.28D, -0.18D, -0.52F);
            this.firstDie.setItemStack(diceItem(1 + (int) ((this.tick * 3) % 6)));
            this.secondDie.setItemStack(diceItem(1 + (int) ((this.tick * 5 + 2) % 6)));
            if (this.tick % 4L == 0L) {
                this.broadcastSound(Sound.ENTITY_ITEM_PICKUP, 0.45F, 0.85F);
            }
            return;
        }
        if (this.tick == ROLL_TICKS + 1L) {
            this.revealResult();
            return;
        }
        if (this.tick >= ROLL_TICKS + REVEAL_TICKS) {
            this.finish();
        }
    }

    private void revealResult() {
        if (this.roll == null || this.firstDie == null || this.secondDie == null) {
            return;
        }
        this.positionSettledDie(this.firstDie, -DIE_X_OFFSET, 18.0F);
        this.positionSettledDie(this.secondDie, DIE_X_OFFSET, -18.0F);
        this.firstDie.setItemStack(diceItem(this.roll.firstDie()));
        this.secondDie.setItemStack(diceItem(this.roll.secondDie()));
        Component text = Component.text(this.session.plugin().messages().plain(
            this.session.publicLocale(),
            "table.dice_roll_reveal",
            this.session.plugin().messages().number(this.session.publicLocale(), "left", this.roll.firstDie()),
            this.session.plugin().messages().number(this.session.publicLocale(), "right", this.roll.secondDie()),
            this.session.plugin().messages().number(this.session.publicLocale(), "total", this.roll.total())
        ));
        this.resultLabel = this.resultLabel == null
            ? this.spawnResultLabel(text)
            : this.updateResultLabel(text);
        this.broadcastSound(Sound.BLOCK_NOTE_BLOCK_BELL, 0.8F, 1.25F);
    }

    private TextDisplay updateResultLabel(Component text) {
        if (this.resultLabel == null) {
            return this.spawnResultLabel(text);
        }
        this.resultLabel.text(text);
        this.teleport(this.resultLabel, this.center.clone().add(0.0D, RESULT_LABEL_Y, 0.0D));
        return this.resultLabel;
    }

    private TextDisplay spawnResultLabel(Component text) {
        // See spawnDie: avoid the org.bukkit.util.Consumer overload that 1.21+ removed.
        TextDisplay spawned = this.world.spawn(
            this.center.clone().add(0.0D, RESULT_LABEL_Y, 0.0D),
            TextDisplay.class
        );
        spawned.setPersistent(false);
        spawned.text(text);
        spawned.setSeeThrough(false);
        spawned.setShadowed(true);
        spawned.setDefaultBackground(false);
        spawned.setBillboard(Display.Billboard.CENTER);
        spawned.setLineWidth(180);
        spawned.setViewRange(32.0F);
        spawned.setBrightness(new Display.Brightness(15, 15));
        TextDisplayBackgrounds.set(spawned, RESULT_LABEL_BACKGROUND);
        return spawned;
    }

    private void animateRollingDie(ItemDisplay display, double baseX, long tick, double driftZ, double tiltBias, float yawBias) {
        double progress = tick / (double) ROLL_TICKS;
        double damping = 1.0D - progress;
        double x = baseX + Math.sin(progress * Math.PI * 2.2D + yawBias) * 0.08D * damping;
        double z = driftZ * damping + Math.cos(progress * Math.PI * 2.8D + yawBias) * 0.04D;
        double y = DIE_Y + Math.sin(progress * Math.PI * 4.0D) * 0.06D * damping;
        this.teleport(display, this.center.clone().add(x, y, z));
        display.setTransformation(new Transformation(
            this.zeroTranslation,
            new Quaternionf().rotateXYZ(
                (float) (progress * Math.PI * 8.0D + tiltBias),
                (float) (progress * Math.PI * 6.0D + yawBias),
                (float) (progress * Math.PI * 5.0D - tiltBias)
            ),
            this.dieScaleVector,
            new Quaternionf()
        ));
    }

    private void positionSettledDie(ItemDisplay display, double xOffset, float yaw) {
        this.teleport(display, this.baseLocation(xOffset));
        display.setRotation(yaw, 0.0F);
        display.setTransformation(new Transformation(
            this.zeroTranslation,
            new Quaternionf().rotateXYZ(0.15F, (float) Math.toRadians(yaw), 0.0F),
            this.dieScaleVector,
            this.identityRotation
        ));
    }

    private ItemDisplay spawnDie(Location location, int point) {
        // World.spawn(Location, Class, Consumer) binds to the deprecated
        // org.bukkit.util.Consumer overload under the 1.20.1 dev bundle, which
        // Paper 1.21+ has removed. Use the long-stable two-argument spawn and
        // configure the entity afterwards. See DisplayEntities for the same fix.
        ItemDisplay spawned = this.world.spawn(location, ItemDisplay.class);
        spawned.setPersistent(false);
        spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
        spawned.setInterpolationDuration(1);
        spawned.setInterpolationDelay(0);
        PaperCompatibility.setTeleportDuration(spawned, 1);
        spawned.setViewRange(20.0F);
        spawned.setShadowRadius(0.0F);
        spawned.setShadowStrength(0.0F);
        spawned.setDisplayWidth(0.45F * DIE_SCALE);
        spawned.setDisplayHeight(0.45F * DIE_SCALE);
        spawned.setBillboard(Display.Billboard.FIXED);
        spawned.setBrightness(new Display.Brightness(15, 15));
        spawned.setItemStack(diceItem(point));
        spawned.setTransformation(new Transformation(
            this.zeroTranslation,
            this.identityRotation,
            this.dieScaleVector,
            this.identityRotation
        ));
        return spawned;
    }

    private Location baseLocation(double xOffset) {
        return this.center.clone().add(xOffset, DIE_Y, 0.0D);
    }

    private void teleport(Entity entity, Location location) {
        this.session.plugin().scheduler().teleport(entity, location);
    }

    private void finish() {
        Runnable callback = this.onComplete;
        this.clear();
        if (callback != null) {
            callback.run();
        }
    }

    private void removeEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        if (this.session.plugin().craftEngine() != null) {
            this.session.plugin().craftEngine().unregisterCullableEntity(entity);
        }
        if (!entity.isDead()) {
            this.session.plugin().scheduler().removeEntity(entity);
        }
    }

    private void broadcastSound(Sound sound, float volume, float pitch) {
        for (Player viewer : this.audience) {
            if (viewer != null && viewer.isOnline()) {
                viewer.playSound(viewer.getLocation(), sound, volume, pitch);
            }
        }
    }

    private List<Player> onlineAudienceSnapshot() {
        List<Player> viewers = this.session.viewers();
        if (viewers.isEmpty()) {
            return List.of();
        }
        List<Player> online = new ArrayList<>(viewers.size());
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) {
                online.add(viewer);
            }
        }
        return List.copyOf(online);
    }

    private static ItemStack diceItem(int point) {
        return DICE_ITEM_CACHE.computeIfAbsent(point, TableDiceAnimationCoordinator::createDiceItem);
    }

    private static ItemStack createDiceItem(int point) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        PaperCompatibility.applyItemModel(meta, new NamespacedKey("mahjongcraft", "dice/" + point));
        stack.setItemMeta(meta);
        return stack;
    }
}
