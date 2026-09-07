package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
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
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.EmployeeService.EmployeeRequest;
import com.clinicos.staff.api.EmployeeService.EmployeeValidationException;
import com.clinicos.staff.api.EmployeeService.StaffRole;

@SpringBootTest(classes = Application.class)
class DefaultEmployeeServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultEmployeeService employeeService;

    private UUID clinicA;
    private UUID clinicB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (Connection connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection);
            clinicB = TestFixtures.insertClinic(connection);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createThenListReturnsEmployee() {
        TenantContext.set(clinicA);
        employeeService.create(clinicA, request("محمود سمير", StaffRole.ASSISTANT, "5000", "1500",
                LocalTime.of(9, 0), LocalTime.of(17, 0), true));

        List<Employee> employees = employeeService.list(clinicA);

        assertThat(employees).extracting(Employee::name).containsExactly("محمود سمير");
        Employee employee = employees.get(0);
        assertThat(employee.staffRole()).isEqualTo(StaffRole.ASSISTANT);
        assertThat(employee.basePay()).isEqualByComparingTo("5000");
        assertThat(employee.maxIncentive()).isEqualByComparingTo("1500");
        assertThat(employee.customShift()).isTrue();
        assertThat(employee.shiftStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(employee.shiftEnd()).isEqualTo(LocalTime.of(17, 0));
        assertThat(employee.hiredAt()).isEqualTo(LocalDate.now());
        assertThat(employee.archivedAt()).isNull();
    }

    @Test
    void updateModifiesFields() {
        TenantContext.set(clinicA);
        Employee created = employeeService.create(clinicA,
                request("محمود", StaffRole.RECEPTIONIST, "4000", null, null, null, false));

        employeeService.update(clinicA, created.id(),
                request("محمود سمير", StaffRole.ASSISTANT, "5200", "2000", null, null, false));

        Employee updated = employeeService.list(clinicA).get(0);
        assertThat(updated.name()).isEqualTo("محمود سمير");
        assertThat(updated.staffRole()).isEqualTo(StaffRole.ASSISTANT);
        assertThat(updated.basePay()).isEqualByComparingTo("5200");
        assertThat(updated.customShift()).isFalse();
    }

    @Test
    void clearingCustomShiftNullsShiftTimes() {
        TenantContext.set(clinicA);
        Employee created = employeeService.create(clinicA,
                request("محمود", StaffRole.ASSISTANT, "5000", null, LocalTime.of(9, 0), LocalTime.of(17, 0), true));

        employeeService.update(clinicA, created.id(),
                request("محمود", StaffRole.ASSISTANT, "5000", null, null, null, false));

        Employee updated = employeeService.list(clinicA).get(0);
        assertThat(updated.customShift()).isFalse();
        assertThat(updated.shiftStart()).isNull();
        assertThat(updated.shiftEnd()).isNull();
    }

    @Test
    void archiveExcludesFromListAndSecondArchiveFails() {
        TenantContext.set(clinicA);
        Employee created = employeeService.create(clinicA,
                request("محمود", StaffRole.ASSISTANT, "5000", null, null, null, false));

        employeeService.archive(clinicA, created.id());

        assertThat(employeeService.list(clinicA)).isEmpty();
        assertThatThrownBy(() -> employeeService.archive(clinicA, created.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("الموظف غير موجود");
    }

    @Test
    void archiveNullsLinkedMembershipEmployeeReference() throws Exception {
        TenantContext.set(clinicA);
        Employee created = employeeService.create(clinicA,
                request("محمود", StaffRole.RECEPTIONIST, "4000", null, null, null, false));
        UUID membershipId;
        try (Connection connection = superuser()) {
            UUID userId = TestFixtures.insertUser(connection, clinicA);
            membershipId = TestFixtures.insertMembership(connection, clinicA, userId, "receptionist");
            DSL.using(connection, SQLDialect.POSTGRES)
                    .update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, created.id())
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .execute();
        }

        employeeService.archive(clinicA, created.id());

        try (Connection connection = superuser()) {
            UUID linkedEmployeeId = DSL.using(connection, SQLDialect.POSTGRES)
                    .select(MEMBERSHIP.EMPLOYEE_ID)
                    .from(MEMBERSHIP)
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .fetchOne(MEMBERSHIP.EMPLOYEE_ID);
            assertThat(linkedEmployeeId).isNull();
        }
    }

    @Test
    void rejectsBlankNameNegativePayAndPartialCustomShift() {
        TenantContext.set(clinicA);
        assertThrows(EmployeeValidationException.class,
                () -> employeeService.create(clinicA, request("  ", StaffRole.ASSISTANT, null, null, null, null, false)));
        assertThrows(EmployeeValidationException.class,
                () -> employeeService.create(clinicA, request("محمود", StaffRole.ASSISTANT, "-1", null, null, null, false)));
        assertThrows(EmployeeValidationException.class,
                () -> employeeService.create(clinicA, request("محمود", StaffRole.ASSISTANT, null, null, LocalTime.of(9, 0), null, true)));
        assertThrows(EmployeeValidationException.class,
                () -> employeeService.create(clinicA, request("محمود", StaffRole.ASSISTANT, null, "-5", null, null, false)));
    }

    @Test
    void partialCustomShiftReportsShiftFieldError() {
        TenantContext.set(clinicA);
        EmployeeValidationException exception = assertThrows(EmployeeValidationException.class,
                () -> employeeService.create(clinicA,
                        request("محمود", StaffRole.ASSISTANT, null, null, LocalTime.of(9, 0), null, true)));

        assertThat(exception.fieldErrors()).containsKey("shift");
    }

    @Test
    void unknownRoleCodeIsRejected() {
        assertThatThrownBy(() -> StaffRole.fromCode("doctor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المسمى الوظيفي غير معروف");
    }

    @Test
    void crossClinicRowsAreInvisibleAndNotUpdatable() throws Exception {
        UUID otherClinicEmployee;
        try (Connection connection = superuser()) {
            otherClinicEmployee = DSL.using(connection, SQLDialect.POSTGRES)
                    .insertInto(EMPLOYEE, EMPLOYEE.ID, EMPLOYEE.CLINIC_ID, EMPLOYEE.NAME, EMPLOYEE.STAFF_ROLE,
                            EMPLOYEE.BASE_PAY, EMPLOYEE.MAX_INCENTIVE)
                    .values(UUID.randomUUID(), clinicB, "موظف عيادة أخرى",
                            com.clinicos.shared.jooq.enums.StaffRole.assistant, BigDecimal.ZERO, BigDecimal.ZERO)
                    .returningResult(EMPLOYEE.ID)
                    .fetchOne(EMPLOYEE.ID);
        }

        TenantContext.set(clinicA);

        assertThat(employeeService.list(clinicA)).isEmpty();
        assertThatThrownBy(() -> employeeService.update(clinicA, otherClinicEmployee,
                request("محمود", StaffRole.ASSISTANT, null, null, null, null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("الموظف غير موجود");
    }

    private static EmployeeRequest request(String name, StaffRole role, String basePay, String maxIncentive,
            LocalTime shiftStart, LocalTime shiftEnd, boolean customShift) {
        return new EmployeeRequest(name, role,
                basePay == null ? null : new BigDecimal(basePay),
                maxIncentive == null ? null : new BigDecimal(maxIncentive),
                shiftStart, shiftEnd, customShift, null);
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}