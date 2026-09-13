package com.clinicos.clinicconfig.internal;

import static com.clinicos.shared.jooq.tables.ClinicHoliday.CLINIC_HOLIDAY;
import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.shared.jooq.tables.records.ClinicHolidayRecord;

@Service
public class DefaultWorkCalendarService implements WorkCalendarService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultWorkCalendarService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public boolean isWorkday(UUID clinicId, LocalDate date, UUID employeeId) {
        return transactionTemplate.execute(status -> isWorkdayTx(clinicId, date, employeeId, settingsWeekdays(clinicId)));
    }

    @Override
    public int workdaysBetween(UUID clinicId, LocalDate startInclusive, LocalDate endInclusive, UUID employeeId) {
        return transactionTemplate.execute(status -> {
            Short[] weekdays = settingsWeekdays(clinicId);
            int count = 0;
            for (LocalDate d = startInclusive; !d.isAfter(endInclusive); d = d.plusDays(1)) {
                if (isWorkdayTx(clinicId, d, employeeId, weekdays)) {
                    count++;
                }
            }
            return count;
        });
    }

    @Override
    public List<Holiday> listHolidays(UUID clinicId) {
        return transactionTemplate.execute(status ->
                dsl.select(CLINIC_HOLIDAY.fields())
                        .select(EMPLOYEE.NAME)
                        .from(CLINIC_HOLIDAY)
                        .leftJoin(EMPLOYEE).on(EMPLOYEE.ID.eq(CLINIC_HOLIDAY.EMPLOYEE_ID))
                        .where(CLINIC_HOLIDAY.CLINIC_ID.eq(clinicId))
                        .orderBy(CLINIC_HOLIDAY.HOLIDAY_DATE.asc())
                        .fetch(this::toHoliday));
    }

    @Override
    public Holiday addHoliday(UUID clinicId, HolidayRequest request) {
        if (request.date() == null) {
            throw new IllegalArgumentException("تاريخ الإجازة مطلوب");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("اسم الإجازة مطلوب");
        }
        return transactionTemplate.execute(status -> {
            if (request.employeeId() != null
                    && !dsl.fetchExists(EMPLOYEE, EMPLOYEE.ID.eq(request.employeeId()).and(EMPLOYEE.CLINIC_ID.eq(clinicId)))) {
                throw new IllegalArgumentException("الموظف غير موجود");
            }
            if (duplicateHoliday(clinicId, request.date(), request.employeeId())) {
                throw new IllegalArgumentException("هذه الإجازة مسجلة مسبقاً");
            }
            UUID id = UUID.randomUUID();
            dsl.insertInto(CLINIC_HOLIDAY)
                    .set(CLINIC_HOLIDAY.ID, id)
                    .set(CLINIC_HOLIDAY.CLINIC_ID, clinicId)
                    .set(CLINIC_HOLIDAY.HOLIDAY_DATE, request.date())
                    .set(CLINIC_HOLIDAY.NAME, request.name().trim())
                    .set(CLINIC_HOLIDAY.EMPLOYEE_ID, request.employeeId())
                    .execute();
            return new Holiday(id, request.date(), request.name().trim(), request.employeeId(), null);
        });
    }

    @Override
    public void removeHoliday(UUID clinicId, UUID holidayId) {
        transactionTemplate.executeWithoutResult(status -> {
            int removed = dsl.deleteFrom(CLINIC_HOLIDAY)
                    .where(CLINIC_HOLIDAY.CLINIC_ID.eq(clinicId))
                    .and(CLINIC_HOLIDAY.ID.eq(holidayId))
                    .execute();
            if (removed == 0) {
                throw new IllegalArgumentException("لا يوجد يوم إجازة بهذا المعرف");
            }
        });
    }

    @Override
    public List<Integer> workingWeekdays(UUID clinicId) {
        return transactionTemplate.execute(status -> toIntegers(settingsWeekdays(clinicId)));
    }

    @Override
    public void setWorkingWeekdays(UUID clinicId, List<Integer> weekdays) {
        validateWeekdays(weekdays);
        transactionTemplate.executeWithoutResult(status ->
                dsl.update(CLINIC_SETTINGS)
                        .set(CLINIC_SETTINGS.WORKING_WEEKDAYS, toShorts(weekdays))
                        .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                        .execute());
    }

    private boolean isWorkdayTx(UUID clinicId, LocalDate date, UUID employeeId, Short[] weekdays) {
        int dow = date.getDayOfWeek().getValue();
        boolean onMask = false;
        for (Short weekday : weekdays) {
            if (weekday != null && weekday == dow) {
                onMask = true;
                break;
            }
        }
        if (!onMask) {
            return false;
        }
        Condition holidayMatch = CLINIC_HOLIDAY.EMPLOYEE_ID.isNull();
        if (employeeId != null) {
            holidayMatch = holidayMatch.or(CLINIC_HOLIDAY.EMPLOYEE_ID.eq(employeeId));
        }
        return !dsl.fetchExists(CLINIC_HOLIDAY, CLINIC_HOLIDAY.CLINIC_ID.eq(clinicId)
                .and(CLINIC_HOLIDAY.HOLIDAY_DATE.eq(date))
                .and(holidayMatch));
    }

    private boolean duplicateHoliday(UUID clinicId, LocalDate date, UUID employeeId) {
        if (employeeId == null) {
            return dsl.fetchExists(CLINIC_HOLIDAY, CLINIC_HOLIDAY.CLINIC_ID.eq(clinicId)
                    .and(CLINIC_HOLIDAY.HOLIDAY_DATE.eq(date))
                    .and(CLINIC_HOLIDAY.EMPLOYEE_ID.isNull()));
        }
        return dsl.fetchExists(CLINIC_HOLIDAY, CLINIC_HOLIDAY.CLINIC_ID.eq(clinicId)
                .and(CLINIC_HOLIDAY.HOLIDAY_DATE.eq(date))
                .and(CLINIC_HOLIDAY.EMPLOYEE_ID.eq(employeeId)));
    }

    private Short[] settingsWeekdays(UUID clinicId) {
        var settings = dsl.selectFrom(CLINIC_SETTINGS)
                .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (settings == null) {
            throw new IllegalArgumentException("إعدادات العيادة غير موجودة");
        }
        return settings.getWorkingWeekdays();
    }

    private void validateWeekdays(List<Integer> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            throw new IllegalArgumentException("أيام العمل يجب أن تتضمن يوماً واحداً على الأقل");
        }
        Set<Integer> seen = new HashSet<>();
        for (Integer weekday : weekdays) {
            if (weekday == null || weekday < 1 || weekday > 7) {
                throw new IllegalArgumentException("قيمة يوم العمل يجب أن تكون بين 1 (الاثنين) و 7 (الأحد)");
            }
            if (!seen.add(weekday)) {
                throw new IllegalArgumentException("لا يمكن تكرار نفس اليوم");
            }
        }
    }

    private Holiday toHoliday(org.jooq.Record record) {
        ClinicHolidayRecord r = record.into(CLINIC_HOLIDAY);
        return new Holiday(r.getId(), r.getHolidayDate(), r.getName(), r.getEmployeeId(),
                record.getValue(EMPLOYEE.NAME));
    }

    private static List<Integer> toIntegers(Short[] weekdays) {
        List<Integer> result = new ArrayList<>(weekdays.length);
        for (Short weekday : weekdays) {
            result.add(weekday != null ? weekday.intValue() : null);
        }
        return result;
    }

    private static Short[] toShorts(List<Integer> weekdays) {
        Short[] result = new Short[weekdays.size()];
        for (int i = 0; i < weekdays.size(); i++) {
            result[i] = weekdays.get(i).shortValue();
        }
        return result;
    }
}