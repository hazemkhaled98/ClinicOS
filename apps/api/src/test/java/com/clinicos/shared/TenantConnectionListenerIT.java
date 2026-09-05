package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.Clinic.CLINIC;
import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;

@SpringBootTest(classes = Application.class)
class TenantConnectionListenerIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID clinicA;
    private UUID clinicB;

    @BeforeEach
    void seedTwoClinics() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicA = insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void transactionWithNoTenantBoundFailsBeforeTouchingTheDatabase() {
        TenantContext.clear();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> countClinicSettingsRows()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound");
    }

    @Test
    void transactionSeesOnlyItsOwnTenantsRows() {
        TenantContext.set(clinicA);
        long visibleToClinicA = transactionTemplate.execute(status -> countClinicSettingsRows());

        TenantContext.set(clinicB);
        long visibleToClinicB = transactionTemplate.execute(status -> countClinicSettingsRows());

        assertThat(visibleToClinicA).isEqualTo(1);
        assertThat(visibleToClinicB).isEqualTo(1);
    }

    private long countClinicSettingsRows() {
        Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        return DSL.using(connection, SQLDialect.POSTGRES).fetchCount(CLINIC_SETTINGS);
    }

    private UUID insertClinic(Connection connection, String name, String slug) {
        DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
        UUID clinicId = dsl.insertInto(CLINIC, CLINIC.NAME, CLINIC.SLUG)
                .values(name, slug)
                .returningResult(CLINIC.ID)
                .fetchOne(CLINIC.ID);
        dsl.insertInto(CLINIC_SETTINGS, CLINIC_SETTINGS.CLINIC_ID)
                .values(clinicId)
                .execute();
        return clinicId;
    }
}
