package top.ellan.mahjong.application.table;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import top.ellan.mahjong.domain.table.TableId;

/** Lock-free table lookup used by the interaction router. */
public final class TableActorRegistry {
    private final ConcurrentHashMap<TableId, TableActionEndpoint> actors =
            new ConcurrentHashMap<>();

    public void register(TableId tableId, TableActionEndpoint actor) {
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(actor, "actor");
        if (actors.putIfAbsent(tableId, actor) != null) {
            throw new IllegalStateException("Table actor already registered: " + tableId);
        }
    }

    public Optional<TableActionEndpoint> find(TableId tableId) {
        return Optional.ofNullable(actors.get(Objects.requireNonNull(tableId, "tableId")));
    }

    public boolean replace(
            TableId tableId,
            TableActionEndpoint expected,
            TableActionEndpoint replacement) {
        return actors.replace(
                Objects.requireNonNull(tableId, "tableId"),
                Objects.requireNonNull(expected, "expected"),
                Objects.requireNonNull(replacement, "replacement"));
    }

    public boolean remove(TableId tableId, TableActionEndpoint expected) {
        return actors.remove(
                Objects.requireNonNull(tableId, "tableId"),
                Objects.requireNonNull(expected, "expected"));
    }

    public int size() {
        return actors.size();
    }

    public List<CompletionStage<Void>> closeAll() {
        List<TableActionEndpoint> snapshot = List.copyOf(actors.values());
        actors.clear();
        return snapshot.stream().map(TableActionEndpoint::closeAndDrain).toList();
    }
}
