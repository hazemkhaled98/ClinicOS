package com.clinicos.staff.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class AttendanceStatusTest {

    private static final LocalTime SHIFT_START = LocalTime.of(9, 0);
    private static final LocalTime SHIFT_END = LocalTime.of(17, 0);
    private static final int GRACE = 15;

    @Test
    void checkInExactlyAtShiftStart_isOnTime() {
        var label = AttendanceStatus.checkInLabel(LocalTime.of(9, 0), SHIFT_START, SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("في الميعاد");
        assertThat(label.ok()).isTrue();
    }

    @Test
    void checkInExactlyAtShiftStartPlusGrace_isOnTime() {
        var label = AttendanceStatus.checkInLabel(LocalTime.of(9, 15), SHIFT_START, SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("في الميعاد");
        assertThat(label.ok()).isTrue();
    }

    @Test
    void checkInOneMinuteAfterGracePeriod_isLate() {
        var label = AttendanceStatus.checkInLabel(LocalTime.of(9, 16), SHIFT_START, SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("متأخّر");
        assertThat(label.ok()).isFalse();
    }

    @Test
    void checkInExactlyAtShiftEnd_isAfterHours() {
        var label = AttendanceStatus.checkInLabel(LocalTime.of(17, 0), SHIFT_START, SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("بعد الدوام");
        assertThat(label.ok()).isFalse();
    }

    @Test
    void checkInAfterShiftEnd_isAfterHours() {
        var label = AttendanceStatus.checkInLabel(LocalTime.of(18, 30), SHIFT_START, SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("بعد الدوام");
        assertThat(label.ok()).isFalse();
    }

    @Test
    void checkOutExactlyAtShiftEndMinusGrace_isFullShift() {
        var label = AttendanceStatus.checkOutLabel(LocalTime.of(16, 45), SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("ميعاد كامل");
        assertThat(label.ok()).isTrue();
    }

    @Test
    void checkOutOneMinuteBeforeGracePeriodEnds_isEarly() {
        var label = AttendanceStatus.checkOutLabel(LocalTime.of(16, 44), SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("انصراف مبكّر");
        assertThat(label.ok()).isFalse();
    }

    @Test
    void checkOutAtShiftEnd_isFullShift() {
        var label = AttendanceStatus.checkOutLabel(LocalTime.of(17, 0), SHIFT_END, GRACE);
        assertThat(label.label()).isEqualTo("ميعاد كامل");
        assertThat(label.ok()).isTrue();
    }
}