package top.ellan.mahjong.plugin.bootstrap.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Guards the explicit JDBC driver resolution required by shaded plugin runtimes. */
class DatabaseBootstrapTest {

    @Test
    void h2UrlResolvesToH2Driver() {
        assertEquals(
                "org.h2.Driver",
                DatabaseBootstrap.driverClassName("jdbc:h2:file:./data/mahjong;MODE=PostgreSQL"));
    }

    @Test
    void mariadbUrlResolvesToMariadbDriver() {
        assertEquals(
                "org.mariadb.jdbc.Driver",
                DatabaseBootstrap.driverClassName("jdbc:mariadb://localhost:3306/mahjong"));
    }

    @Test
    void mysqlUrlResolvesToMysqlDriver() {
        assertEquals(
                "com.mysql.cj.jdbc.Driver",
                DatabaseBootstrap.driverClassName("jdbc:mysql://localhost:3306/mahjong"));
    }

    @Test
    void unknownSchemeIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseBootstrap.driverClassName("jdbc:postgresql://localhost/mahjong"));
    }
}
