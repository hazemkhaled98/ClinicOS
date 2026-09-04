package com.clinicos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers Postgres + Flyway bootstrap for integration test base
 * classes that can't all extend the same class (single inheritance) --
 * {@link AbstractPostgresIntegrationTest} and any Playwright IT base both
 * need this same setup. See {@link AbstractPostgresIntegrationTest}'s javadoc
 * for why the container is started once, JVM-wide, rather than per test
 * class.
 */
public final class PostgresTestSupport {

    public static final String APP_RW_PASSWORD = "test-only-password";

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("clinicos");

    static {
        POSTGRES.start();
    }

    private PostgresTestSupport() {
    }

    public static void migrateAndProvisionAppRw() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("alter role app_rw password '" + APP_RW_PASSWORD + "'");
        }
    }

    public static void configureDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_rw");
        registry.add("spring.datasource.password", () -> APP_RW_PASSWORD);
    }
}
