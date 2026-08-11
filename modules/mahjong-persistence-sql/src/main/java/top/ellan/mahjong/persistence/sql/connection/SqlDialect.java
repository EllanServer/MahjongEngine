package top.ellan.mahjong.persistence.sql.connection;

import java.util.Locale;

/** Small DDL differences across the supported databases. */
public enum SqlDialect {
    H2("BLOB"),
    MYSQL("LONGBLOB"),
    MARIADB("LONGBLOB");

    private final String binaryType;

    SqlDialect(String binaryType) {
        this.binaryType = binaryType;
    }

    public String binaryType() {
        return binaryType;
    }

    public static SqlDialect fromProductName(String productName) {
        String normalized = productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("mariadb")) {
            return MARIADB;
        }
        if (normalized.contains("mysql")) {
            return MYSQL;
        }
        if (normalized.contains("h2")) {
            return H2;
        }
        throw new IllegalArgumentException("Unsupported SQL database: " + productName);
    }
}
