package com.clinicos.staff.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

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