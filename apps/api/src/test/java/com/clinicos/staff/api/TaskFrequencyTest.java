package com.clinicos.staff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class TaskFrequencyTest {

    @Test
    void neverCompleted_isDue() {
        TaskFrequency.DueStatus status = TaskFrequency.dueStatus(LocalDate.now(), null, 7);
        assertThat(status.state()).isEqualTo("due");
        assertThat(status.label()).isEqualTo("🔔 موعدها دلوقتي");
    }

    @Test
    void weeklyCompleted7DaysAgo_isDue() {
        LocalDate completed = LocalDate.now().minusDays(7);
        TaskFrequency.DueStatus status = TaskFrequency.dueStatus(LocalDate.now(), completed, 7);
        assertThat(status.state()).isEqualTo("due");
    }

    @Test
    void weeklyCompletedToday_isUpcoming() {
        LocalDate completed = LocalDate.now();
        TaskFrequency.DueStatus status = TaskFrequency.dueStatus(LocalDate.now(), completed, 7);
        assertThat(status.state()).isEqualTo("upcoming");
        assertThat(status.label()).contains("7 يوم");
    }

    @Test
    void weeklyCompleted4DaysAgo_isDue() {
        LocalDate completed = LocalDate.now().minusDays(4);
        TaskFrequency.DueStatus status = TaskFrequency.dueStatus(LocalDate.now(), completed, 7);
        assertThat(status.state()).isEqualTo("due");
    }

    @Test
    void weeklyCompleted11DaysAgo_isOverdue() {
        LocalDate completed = LocalDate.now().minusDays(11);
        TaskFrequency.DueStatus status = TaskFrequency.dueStatus(LocalDate.now(), completed, 7);
        assertThat(status.state()).isEqualTo("overdue");
        assertThat(status.label()).contains("1 يوم");
    }

    @Test
    void monthlyCompleted35DaysAgo_isOverdue() {
        LocalDate completed = LocalDate.now().minusDays(35);
        TaskFrequency.DueStatus status = TaskFrequency.dueStatus(LocalDate.now(), completed, 30);
        assertThat(status.state()).isEqualTo("overdue");
        assertThat(status.label()).contains("2 يوم");
    }

    @Test
    void customEveryTwoWeeks_hasPeriod14() {
        int period = TaskFrequency.periodDays("custom", 2, "week");
        assertThat(period).isEqualTo(14);
    }

    @Test
    void customEveryThreeDays_hasPeriod3() {
        int period = TaskFrequency.periodDays("custom", 3, "day");
        assertThat(period).isEqualTo(3);
    }

    @Test
    void customEveryOneMonth_hasPeriod30() {
        int period = TaskFrequency.periodDays("custom", 1, "month");
        assertThat(period).isEqualTo(30);
    }

    @Test
    void weeklyPeriod_is7() {
        assertThat(TaskFrequency.periodDays("weekly", null, null)).isEqualTo(7);
    }

    @Test
    void monthlyPeriod_is30() {
        assertThat(TaskFrequency.periodDays("monthly", null, null)).isEqualTo(30);
    }

    @Test
    void dailyFrequency_throws() {
        assertThatThrownBy(() -> TaskFrequency.periodDays("daily", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("التكرار اليومي لا يُحسب كفترة دورية");
    }

    @Test
    void unknownFrequency_throws() {
        assertThatThrownBy(() -> TaskFrequency.periodDays("unknown", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownUnit_throws() {
        assertThatThrownBy(() -> TaskFrequency.periodDays("custom", 2, "hour"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}