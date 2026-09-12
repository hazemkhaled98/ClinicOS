package com.clinicos.staff.api;

import java.time.LocalTime;

public final class AttendanceStatus {

    private AttendanceStatus() {
    }

    public record AttendanceLabel(String label, boolean ok) {
    }

    public static AttendanceLabel checkInLabel(LocalTime checkInTime, LocalTime shiftStart,
            LocalTime shiftEnd, int graceMinutes) {
        if (!checkInTime.isBefore(shiftEnd)) {
            return new AttendanceLabel("بعد الدوام", false);
        }
        if (!checkInTime.isAfter(shiftStart.plusMinutes(graceMinutes))) {
            return new AttendanceLabel("في الميعاد", true);
        }
        return new AttendanceLabel("متأخّر", false);
    }

    public static AttendanceLabel checkOutLabel(LocalTime checkOutTime, LocalTime shiftEnd,
            int graceMinutes) {
        boolean full = !checkOutTime.isBefore(shiftEnd.minusMinutes(graceMinutes));
        return new AttendanceLabel(full ? "ميعاد كامل" : "انصراف مبكّر", full);
    }

    public static boolean isOnTime(LocalTime checkInTime, LocalTime shiftStart, int graceMinutes) {
        return !checkInTime.isAfter(shiftStart.plusMinutes(graceMinutes));
    }

    public static boolean isFullShift(LocalTime checkOutTime, LocalTime shiftEnd, int graceMinutes) {
        return !checkOutTime.isBefore(shiftEnd.minusMinutes(graceMinutes));
    }
}