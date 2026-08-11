package top.ellan.mahjong.craftengine.privateview;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Latest-only keyed work buffer; superseded player projections never become region tasks. */
final class LatestTaskBuffer<K> {
    private final ConcurrentHashMap<K, Runnable> tasks = new ConcurrentHashMap<>();

    void offer(K key, Runnable task) {
        tasks.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(task, "task"));
    }

    int drain(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        int processed = 0;
        for (var entry : tasks.entrySet()) {
            if (processed >= limit) {
                break;
            }
            if (!tasks.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            entry.getValue().run();
            processed++;
        }
        return processed;
    }

    boolean isEmpty() {
        return tasks.isEmpty();
    }

    int size() {
        return tasks.size();
    }

    void clear() {
        tasks.clear();
    }
}
