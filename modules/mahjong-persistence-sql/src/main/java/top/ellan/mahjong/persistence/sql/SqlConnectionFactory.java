package top.ellan.mahjong.persistence.sql;

import java.sql.Connection;
import java.sql.SQLException;

/** Hikari or DriverManager boundary. */
@FunctionalInterface
public interface SqlConnectionFactory {
    Connection open() throws SQLException;
}
