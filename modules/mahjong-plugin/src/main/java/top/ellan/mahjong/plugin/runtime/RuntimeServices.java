package top.ellan.mahjong.plugin.runtime;

import java.util.Objects;
import java.util.Optional;
import top.ellan.mahjong.plugin.RulePackMatchCoordinator;
import top.ellan.mahjong.plugin.bootstrap.rules.RulePackRuntimeServices;
import top.ellan.mahjong.plugin.bootstrap.sql.DatabaseRuntime;

/** Fully initialized restart-scoped service graph. */
public record RuntimeServices(
        DatabaseRuntime database,
        RulePackRuntimeServices rules,
        Optional<RulePackMatchCoordinator> coordinator)
        implements AutoCloseable {
    public RuntimeServices {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public void close() {
        rules.close();
        database.close();
    }
}
