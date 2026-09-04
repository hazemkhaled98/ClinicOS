package com.clinicos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;

/**
 * Proves the two things Phase 0's tenant-context mechanism must guarantee:
 * a transaction with no tenant bound fails loudly instead of quietly
 * returning zero rows, and a transaction with a tenant bound sees only that
 * tenant's rows in an RLS-scoped table.
 */
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

        // Two clinics were seeded, one clinic_settings row each; each tenant
        // must see exactly its own row, never both.
        assertThat(visibleToClinicA).isEqualTo(1);
        assertThat(visibleToClinicB).isEqualTo(1);
    }

    private long countClinicSettingsRows() {
        // The connection returned by DataSourceUtils.getConnection is the
        // transaction-bound connection Spring already owns -- it must NOT be
        // closed here (closing it returns the pooled connection to Hikari
        // mid-transaction, and the later commit fails with "Connection is
        // closed"). Only the statement and result set are closed.
        Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement("select count(*) from clinic_settings");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID insertClinic(Connection connection, String name, String slug) throws Exception {
        UUID clinicId;
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into clinic (name, slug) values (?, ?) returning id")) {
            statement.setString(1, name);
            statement.setString(2, slug);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                clinicId = (UUID) resultSet.getObject(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into clinic_settings (clinic_id) values (?)")) {
            statement.setObject(1, clinicId);
            statement.execute();
        }
        return clinicId;
    }
}
