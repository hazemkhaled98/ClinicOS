package com.clinicos.staff.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface SelfCheckService {

    DayAttendance today(UUID clinicId, UUID employeeId);

    DayAttendance checkIn(UUID clinicId, UUID employeeId);

    DayAttendance checkOut(UUID clinicId, UUID employeeId);

    record DayAttendance(LocalDate date, OffsetDateTime checkedInAt, OffsetDateTime checkedOutAt) {
    }
}