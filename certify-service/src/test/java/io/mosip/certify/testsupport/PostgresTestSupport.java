package io.mosip.certify.testsupport;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates the real Certify status/ledger tables, from the deployment DDL, in a PostgreSQL test database. */
public final class PostgresTestSupport {

    private static final String DDL_DIR = "../db_scripts/inji_certify/ddl/";
    private static final String[] TABLES = {
            "certify-status_list_credential.sql",
            "certify-ledger.sql",
            "certify-credential_status_transaction.sql",
            "certify-status_list_available_indices.sql",
            "certify-status_list_allocation.sql"
    };

    private PostgresTestSupport() {
    }

    /** Whether the deployment DDL can be found from the module directory the tests run in. */
    public static boolean ddlAvailable() {
        return Files.isDirectory(Path.of(DDL_DIR));
    }

    /** Creates the {@code certify} schema (the DDL qualifies some objects with it) and all tables. */
    public static void createSchema(String jdbcUrl, String user, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS certify");
                statement.execute("SET search_path TO certify, public");
            }
            for (String table : TABLES) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(DDL_DIR + table));
            }
        }
    }
}
