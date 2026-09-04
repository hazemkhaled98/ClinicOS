package com.clinicos;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real, Flyway-migrated Postgres
 * instance with the application connecting as {@code app_rw} — exactly as it
 * does in production — so row-level security is actually exercised rather
 * than bypassed by a superuser connection.
 *
 * <p>Sequencing matters: V9__rls_policies.sql creates {@code app_rw} with no
 * password, so this class migrates as the container's own superuser first,
 * gives {@code app_rw} a password, and only then lets Spring's own DataSource
 * (configured to connect as {@code app_rw}) start. {@code spring.flyway.enabled}
 * is disabled so Boot does not re-run migrations as {@code app_rw}, which has
 * no DDL rights.
 *
 * <p>{@code POSTGRES} is declared on this shared base class, so every IT
 * subclass inherits the same static field. The Testcontainers JUnit extension
 * (@Testcontainers/@Container) scopes start/stop to the owning test class —
 * with more than one IT subclass in the same JVM fork, the first class's
 * teardown stops the container while a second class is still resolving its
 * JDBC URL from it, producing sporadic "connection aborted"/"connection is
 * closed" failures. Starting it manually here instead (the documented
 * Testcontainers "singleton container" pattern) makes it a true JVM-wide
 * singleton: started once, never explicitly stopped — Ryuk reaps it when the
 * test run ends.
 *
 * <p>The actual container/Flyway bootstrap lives in {@link PostgresTestSupport}
 * so a test base class that can't extend this one (e.g. a Playwright IT base,
 * since Java has no multiple inheritance) can still share it.
 */
public abstract class AbstractPostgresIntegrationTest {

    protected static final String APP_RW_PASSWORD = PostgresTestSupport.APP_RW_PASSWORD;

    protected static final PostgreSQLContainer<?> POSTGRES = PostgresTestSupport.POSTGRES;

    @BeforeAll
    static void migrateAndProvisionAppRw() throws Exception {
        PostgresTestSupport.migrateAndProvisionAppRw();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.configureDatasourceProperties(registry);
    }
}
