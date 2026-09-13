package com.clinicos.clinicconfig.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
import com.clinicos.clinicconfig.api.WorkCalendarService.Holiday;
import com.clinicos.clinicconfig.api.WorkCalendarService.HolidayRequest;
import com.clinicos.clinicconfig.api.WorkCalendarService.WorkCalendarValidationException;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class WorkCalendarServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultWorkCalendarService workCalendarService;

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
    void weekdayMaskDefaultsToSatThu() {
        TenantContext.set(clinicA);

        assertThat(workCalendarService.workingWeekdays(clinicA)).containsExactly(6, 7, 1, 2, 3, 4);
        assertThat(workCalendarService.isWorkday(clinicA, next(DayOfWeek.FRIDAY), null)).isFalse();
        assertThat(workCalendarService.isWorkday(clinicA, next(DayOfWeek.SATURDAY), null)).isTrue();
    }

    @Test
    void updateWeekdayMaskReplacesMask() {
        TenantContext.set(clinicA);

        workCalendarService.setWorkingWeekdays(clinicA, List.of(1, 2, 3));

        assertThat(workCalendarService.workingWeekdays(clinicA)).containsExactly(1, 2, 3);
        assertThat(workCalendarService.isWorkday(clinicA, next(DayOfWeek.SATURDAY), null)).isFalse();
        assertThat(workCalendarService.isWorkday(clinicA, next(DayOfWeek.MONDAY), null)).isTrue();
    }

    @Test
    void weekdayMaskRejectsEmptyInvalidOutOfRangeAndDuplicate() {
        TenantContext.set(clinicA);

        assertThrows(WorkCalendarValidationException.class,
                () -> workCalendarService.setWorkingWeekdays(clinicA, List.of()));
        WorkCalendarValidationException invalid = assertThrows(WorkCalendarValidationException.class,
                () -> workCalendarService.setWorkingWeekdays(clinicA, List.of(1, 8)));
        assertThat(invalid.fieldErrors()).containsKey("weekdays");
        assertThrows(WorkCalendarValidationException.class,
                () -> workCalendarService.setWorkingWeekdays(clinicA, List.of(1, 1)));

        assertThat(workCalendarService.workingWeekdays(clinicA)).containsExactly(6, 7, 1, 2, 3, 4);
    }

    @Test
    void addAndRemoveClinicWideHoliday() {
        TenantContext.set(clinicA);
        LocalDate day = next(DayOfWeek.SATURDAY);

        Holiday holiday = workCalendarService.addHoliday(clinicA,
                new HolidayRequest(day, "عيد الفطر", null));

        assertThat(holiday.id()).isNotNull();
        assertThat(holiday.employeeId()).isNull();
        assertThat(workCalendarService.listHolidays(clinicA))
                .extracting(Holiday::name).containsExactly("عيد الفطر");
        assertThat(workCalendarService.isWorkday(clinicA, day, null)).isFalse();
        assertThatThrownBy(() -> workCalendarService.addHoliday(clinicA,
                new HolidayRequest(day, "عيد الفطر مكرر", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("مسجلة مسبقاً");

        workCalendarService.removeHoliday(clinicA, holiday.id());

        assertThat(workCalendarService.listHolidays(clinicA)).isEmpty();
        assertThat(workCalendarService.isWorkday(clinicA, day, null)).isTrue();
    }

    @Test
    void perEmployeeHolidayHonoredOnlyForThatEmployee() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeA = insertEmployee(clinicA, "محمود");
        UUID employeeB = insertEmployee(clinicA, "سارة");
        LocalDate day = next(DayOfWeek.SATURDAY);

        workCalendarService.addHoliday(clinicA, new HolidayRequest(day, "إجازة شخصية", employeeA));

        assertThat(workCalendarService.isWorkday(clinicA, day, employeeA)).isFalse();
        assertThat(workCalendarService.isWorkday(clinicA, day, employeeB)).isTrue();
        assertThat(workCalendarService.isWorkday(clinicA, day, null)).isTrue();
        assertThat(workCalendarService.listHolidays(clinicA)).hasSize(1);
        assertThat(workCalendarService.listHolidays(clinicA).get(0).employeeName()).isEqualTo("محمود");
    }

    @Test
    void addHolidayRejectsEmployeeFromAnotherClinic() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeB = insertEmployee(clinicB, "موظف عيادة أخرى");

        assertThatThrownBy(() -> workCalendarService.addHoliday(clinicA,
                new HolidayRequest(next(DayOfWeek.SATURDAY), "إجازة", employeeB)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("الموظف غير موجود");
    }

    @Test
    void holidayAndMaskAreIsolatedBetweenTenants() throws Exception {
        TenantContext.set(clinicA);
        LocalDate day = next(DayOfWeek.SATURDAY);
        Holiday holiday = workCalendarService.addHoliday(clinicA, new HolidayRequest(day, "إجازة عيادة أ", null));
        workCalendarService.setWorkingWeekdays(clinicA, List.of(1, 2, 3));

        TenantContext.set(clinicB);

        assertThat(workCalendarService.listHolidays(clinicB)).isEmpty();
        assertThat(workCalendarService.workingWeekdays(clinicB)).containsExactly(6, 7, 1, 2, 3, 4);
        assertThat(workCalendarService.isWorkday(clinicB, day, null)).isTrue();
        assertThatThrownBy(() -> workCalendarService.removeHoliday(clinicB, holiday.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("لا يوجد يوم إجازة");

        TenantContext.set(clinicA);
        assertThat(workCalendarService.listHolidays(clinicA)).hasSize(1);
    }

    @Test
    void holidayValidationsRejectBlankFields() {
        TenantContext.set(clinicA);

        assertThatThrownBy(() -> workCalendarService.addHoliday(clinicA, new HolidayRequest(null, "اسم", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("تاريخ الإجازة مطلوب");
        assertThatThrownBy(() -> workCalendarService.addHoliday(clinicA,
                new HolidayRequest(next(DayOfWeek.SUNDAY), "  ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("اسم الإجازة مطلوب");
    }

    private UUID insertEmployee(UUID clinicId, String name) throws Exception {
        try (Connection connection = superuser()) {
            return DSL.using(connection, SQLDialect.POSTGRES)
                    .insertInto(EMPLOYEE, EMPLOYEE.ID, EMPLOYEE.CLINIC_ID, EMPLOYEE.NAME,
                            EMPLOYEE.BASE_PAY, EMPLOYEE.MAX_INCENTIVE)
                    .values(UUID.randomUUID(), clinicId, name, BigDecimal.ZERO, BigDecimal.ZERO)
                    .returningResult(EMPLOYEE.ID)
                    .fetchOne(EMPLOYEE.ID);
        }
    }

    private static LocalDate next(DayOfWeek day) {
        return LocalDate.now().with(TemporalAdjusters.next(day));
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}