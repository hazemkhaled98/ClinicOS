package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.ActivityLog.ACTIVITY_LOG;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;

@SpringBootTest(classes = Application.class)
class ActivityLogServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private ActivityLogService activityLogService;

    private UUID clinicId;
    private UUID userId;
    private UUID membershipId;

    @BeforeEach
    void seedClinicAndMembership() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicId = TestFixtures.insertClinic(connection);
            userId = TestFixtures.insertUser(connection, clinicId);
            membershipId = TestFixtures.insertMembership(connection, clinicId, userId);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void loginActivityWriteSucceedsWithClinicBoundAndIsVisibleToSameTenant() throws Exception {
        TenantContext.set(clinicId);

        activityLogService.log(clinicId, membershipId, "login", "session");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            long count = countActivityRowsForClinic(connection, clinicId);
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void loginActivityWriteFailsWithoutClinicBound() {
        TenantContext.clear();

        try {
            activityLogService.log(clinicId, membershipId, "login", "session");
            throw new AssertionError("Expected IllegalStateException because no tenant is bound");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("No tenant bound");
        }
    }

    @Test
    void logSwallowsWriteFailureInsteadOfPropagatingIt() throws Exception {
        TenantContext.set(clinicId);

        activityLogService.log(clinicId, UUID.randomUUID(), "login", "session");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(countActivityRowsForClinic(connection, clinicId)).isZero();
        }
    }

    private long countActivityRowsForClinic(Connection connection, UUID clinic) {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .fetchCount(ACTIVITY_LOG, ACTIVITY_LOG.CLINIC_ID.eq(clinic));
    }
}
