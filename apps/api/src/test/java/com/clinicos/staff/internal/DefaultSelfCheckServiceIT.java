package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.SelfCheck.SELF_CHECK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
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
import com.clinicos.shared.TenantContext;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.SelfCheckService.DayAttendance;

@SpringBootTest(classes = Application.class)
class DefaultSelfCheckServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultSelfCheckService selfCheckService;

    @Autowired
    private EmployeeService employeeService;

    private UUID clinicA;

    @BeforeEach
    void seedClinic() throws Exception {
        try (Connection conn = superuser()) {
            clinicA = TestFixtures.insertClinic(conn);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void today_noCheckIn_returnsNulls() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        DayAttendance today = selfCheckService.today(clinicA, employeeId);
        assertThat(today.date()).isEqualTo(LocalDate.now());
        assertThat(today.checkedInAt()).isNull();
        assertThat(today.checkedOutAt()).isNull();
    }

    @Test
    void checkIn_thenToday_returnsTimes() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        DayAttendance afterIn = selfCheckService.checkIn(clinicA, employeeId);
        assertThat(afterIn.checkedInAt()).isNotNull();
        assertThat(afterIn.checkedOutAt()).isNull();

        DayAttendance today = selfCheckService.today(clinicA, employeeId);
        assertThat(today.checkedInAt()).isEqualTo(afterIn.checkedInAt());
    }

    @Test
    void checkIn_idempotent_returnsExisting() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        DayAttendance first = selfCheckService.checkIn(clinicA, employeeId);
        DayAttendance second = selfCheckService.checkIn(clinicA, employeeId);
        assertThat(second.checkedInAt()).isEqualTo(first.checkedInAt());
        assertThat(second.checkedOutAt()).isNull();
    }

    @Test
    void checkOut_afterCheckIn_setsCheckout() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        selfCheckService.checkIn(clinicA, employeeId);
        DayAttendance afterOut = selfCheckService.checkOut(clinicA, employeeId);
        assertThat(afterOut.checkedOutAt()).isNotNull();
        assertThat(afterOut.checkedInAt()).isNotNull();
    }

    @Test
    void checkOut_withoutCheckIn_throws() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        assertThatThrownBy(() -> selfCheckService.checkOut(clinicA, employeeId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("لازم تسجّل الحضور أولاً");
    }

    @Test
    void checkOut_idempotent_returnsExisting() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        selfCheckService.checkIn(clinicA, employeeId);
        DayAttendance first = selfCheckService.checkOut(clinicA, employeeId);
        DayAttendance second = selfCheckService.checkOut(clinicA, employeeId);
        assertThat(second.checkedOutAt()).isEqualTo(first.checkedOutAt());
    }

    @Test
    void crossClinicRowsAreHiddenByRls() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeA = createEmployee("أحمد");
        selfCheckService.checkIn(clinicA, employeeA);

        UUID clinicB;
        try (Connection conn = superuser()) {
            clinicB = TestFixtures.insertClinic(conn);
        }
        TenantContext.set(clinicB);
        assertThatThrownBy(() -> selfCheckService.checkOut(clinicA, employeeA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("لازم تسجّل الحضور أولاً");
    }

    @Test
    void forMonth_returnsOnlyRowsInMonth() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        selfCheckService.checkIn(clinicA, employeeId);

        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(SELF_CHECK,
                            SELF_CHECK.CLINIC_ID,
                            SELF_CHECK.EMPLOYEE_ID,
                            SELF_CHECK.WORK_DATE,
                            SELF_CHECK.CHECKED_IN_AT)
                    .values(clinicA, employeeId, YearMonth.now().minusMonths(1).atDay(1),
                            OffsetDateTime.now().minusMonths(1))
                    .execute();
        }

        List<LocalDate> dates = selfCheckService.forMonth(clinicA, employeeId, YearMonth.now())
                .stream().map(DayAttendance::date).toList();

        assertThat(dates).containsExactly(LocalDate.now());
    }

    @Test
    void forMonth_crossClinicEmployee_returnsEmpty() throws Exception {
        UUID clinicB;
        UUID employeeB;
        try (Connection conn = superuser()) {
            clinicB = TestFixtures.insertClinic(conn);
        }
        TenantContext.set(clinicB);
        try (Connection conn = superuser()) {
            UUID userB = TestFixtures.insertUser(conn, clinicB);
            UUID membershipB = TestFixtures.insertMembership(conn, clinicB, userB, "assistant");
            employeeB = employeeService.create(clinicB,
                    new EmployeeService.EmployeeRequest("محمد", BigDecimal.ZERO, BigDecimal.ZERO,
                            LocalTime.of(9, 0), LocalTime.of(17, 0), false, null)).id();
            DSL.using(conn, SQLDialect.POSTGRES)
                    .update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, employeeB)
                    .where(MEMBERSHIP.ID.eq(membershipB))
                    .execute();
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(SELF_CHECK,
                            SELF_CHECK.CLINIC_ID,
                            SELF_CHECK.EMPLOYEE_ID,
                            SELF_CHECK.WORK_DATE,
                            SELF_CHECK.CHECKED_IN_AT)
                    .values(clinicB, employeeB, LocalDate.now(), OffsetDateTime.now())
                    .execute();
        }
        TenantContext.set(clinicA);

        assertThat(selfCheckService.forMonth(clinicA, employeeB, YearMonth.now())).isEmpty();
    }

    private UUID createEmployee(String name) {
        EmployeeService.Employee emp = employeeService.create(clinicA,
                new EmployeeService.EmployeeRequest(name, BigDecimal.ZERO, BigDecimal.ZERO,
                        LocalTime.of(9, 0), LocalTime.of(17, 0), false, null));
        return emp.id();
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}