package com.clinicos.staff.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface SelfCheckService {

    DayAttendance today(UUID clinicId, UUID employeeId);

    /** All attendance rows in the given month (evaluation input). */
    List<DayAttendance> forMonth(UUID clinicId, UUID employeeId, YearMonth month);

    DayAttendance checkIn(UUID clinicId, UUID employeeId);

    DayAttendance checkOut(UUID clinicId, UUID employeeId);

    record DayAttendance(LocalDate date, OffsetDateTime checkedInAt, OffsetDateTime checkedOutAt) {
    }
}