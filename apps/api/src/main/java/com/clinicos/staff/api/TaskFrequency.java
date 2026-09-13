package com.clinicos.staff.api;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class TaskFrequency {

    public static final int DUE_PAD = 3;

    private TaskFrequency() {
    }

    public record DueStatus(String state, String label) {
    }

    public static DueStatus dueStatus(LocalDate today, LocalDate lastCompletedDate, int periodDays) {
        if (lastCompletedDate == null) {
            return new DueStatus("due", "🔔 موعدها دلوقتي");
        }
        LocalDate nextDue = lastCompletedDate.plusDays(periodDays);
        long diff = ChronoUnit.DAYS.between(today, nextDue);
        if (diff > DUE_PAD) {
            return new DueStatus("upcoming", "🗓️ جايّة بعد " + diff + " يوم");
        }
        if (diff >= -DUE_PAD) {
            return new DueStatus("due", "🔔 موعدها دلوقتي");
        }
        return new DueStatus("overdue", "⚠️ فات موعدها بـ " + (-diff - DUE_PAD) + " يوم");
    }

    public static int periodDays(String frequency, Integer everyN, String intervalUnit) {
        return switch (frequency) {
            case "weekly" -> 7;
            case "monthly" -> 30;
            case "custom" -> everyN * unitDays(intervalUnit);
            case "daily" -> throw new IllegalArgumentException("التكرار اليومي لا يُحسب كفترة دورية");
            default -> throw new IllegalArgumentException("التكرار غير معروف: " + frequency);
        };
    }

    private static int unitDays(String intervalUnit) {
        return switch (intervalUnit) {
            case "day" -> 1;
            case "week" -> 7;
            case "month" -> 30;
            default -> throw new IllegalArgumentException("الوحدة غير معروفة: " + intervalUnit);
        };
    }
}