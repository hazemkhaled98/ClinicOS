package com.clinicos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that need a real, Flyway-migrated
 * Postgres instance with the application connecting as {@code app_rw} --
 * exactly as it does in production -- so row-level security is actually
 * exercised rather than bypassed by a superuser connection.
 *
 * <p>Sequencing matters: V9__rls_policies.sql creates {@code app_rw} with
 * no password (see docs/roadmap.md's cross-cutting notes), so this class
 * migrates as the container's own superuser first, gives {@code app_rw} a
 * password, and only then lets Spring's own DataSource -- which is
 * configured to connect as {@code app_rw} -- start up.
 */
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    protected static final String APP_RW_PASSWORD = "test-only-password";

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("clinicos");

    @BeforeAll
    static void migrateAndProvisionAppRw() throws Exception {
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

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Already migrated above as superuser; don't let Spring Boot's own
        // Flyway auto-configuration try again as app_rw, which has no DDL rights.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_rw");
        registry.add("spring.datasource.password", () -> APP_RW_PASSWORD);
    }
}
