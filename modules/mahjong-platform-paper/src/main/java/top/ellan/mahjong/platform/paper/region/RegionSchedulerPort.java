package top.ellan.mahjong.platform.paper.region;

/** Schedules one non-blocking task for the next tick on the owning Paper/Folia region. */
@FunctionalInterface
public interface RegionSchedulerPort {
    void nextTick(RegionKey region, Runnable task);
}
