package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.ActivityLog.ACTIVITY_LOG;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    @Test
    void forDayMatchesExactActionsAndPrefixesButNotUnrelatedActions() {
        TenantContext.set(clinicId);
        activityLogService.log(clinicId, membershipId, "inventory.order.place", "purchase_order");
        activityLogService.log(clinicId, membershipId, "inventory.issue", "stock");
        activityLogService.log(clinicId, membershipId, "task.create", "task");
        activityLogService.log(clinicId, membershipId, "task", "task");
        LocalDate day = LocalDate.now(ZoneId.of("Africa/Cairo"));

        assertThat(actions(forDay(clinicId, day, "all"))).hasSize(4);
        assertThat(actions(forDay(clinicId, day, "volume.record,inventory")))
                .containsExactlyInAnyOrder("inventory.order.place", "inventory.issue");
        assertThat(actions(forDay(clinicId, day, "task"))).containsExactlyInAnyOrder("task.create", "task");
    }

    @Test
    void forDayOnlySeesEntriesInsideTheRequestedDay() {
        TenantContext.set(clinicId);
        activityLogService.log(clinicId, membershipId, "login", "session");
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Cairo"));

        assertThat(actions(forDay(clinicId, today, "all"))).containsExactly("login");
        assertThat(actions(forDay(clinicId, today.plusDays(1), "all"))).isEmpty();
    }

    private List<ActivityLogService.Entry> forDay(UUID clinic, LocalDate day, String category) {
        return activityLogService.forDay(clinic, day, category);
    }

    private static List<String> actions(List<ActivityLogService.Entry> entries) {
        return entries.stream().map(ActivityLogService.Entry::action).toList();
    }

    private long countActivityRowsForClinic(Connection connection, UUID clinic) {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .fetchCount(ACTIVITY_LOG, ACTIVITY_LOG.CLINIC_ID.eq(clinic));
    }
}
