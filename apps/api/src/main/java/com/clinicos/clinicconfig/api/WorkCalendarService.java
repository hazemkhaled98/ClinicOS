package com.clinicos.clinicconfig.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Work calendar for one clinic: the weekday mask (which days of the week the
 * clinic works) plus holidays (clinic-wide and per-employee). Attendance
 * scoring uses it to tell a scheduled work day from a day off. Every read and
 * mutation runs inside the clinic bound to the current thread's
 * {@code TenantContext}; callers must guarantee a tenant is bound before
 * invoking.
 *
 * <p>Day-of-week values are ISO-8601: 1=Monday .. 7=Sunday.
 *
 * <p>A {@code null} {@code employeeId} means "clinic-wide only" -- per-employee
 * holidays are ignored (used for clinic-level calendar views and for employees
 * with no holiday rows).
 */
public interface WorkCalendarService {

    boolean isWorkday(UUID clinicId, LocalDate date, UUID employeeId);

    int workdaysBetween(UUID clinicId, LocalDate startInclusive, LocalDate endInclusive, UUID employeeId);

    List<Holiday> listHolidays(UUID clinicId);

    Holiday addHoliday(UUID clinicId, HolidayRequest request);

    void removeHoliday(UUID clinicId, UUID holidayId);

    /** Current working weekday mask as ISO day-of-week ints. */
    List<Integer> workingWeekdays(UUID clinicId);

    /** Replaces the weekday mask (validated 1..7, non-empty, no duplicates). */
    void setWorkingWeekdays(UUID clinicId, List<Integer> weekdays);

    record Holiday(UUID id, LocalDate date, String name, UUID employeeId, String employeeName) {
    }

    record HolidayRequest(LocalDate date, String name, UUID employeeId) {
    }
}