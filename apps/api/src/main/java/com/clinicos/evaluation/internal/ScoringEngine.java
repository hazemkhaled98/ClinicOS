package com.clinicos.evaluation.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.staff.api.EvaluationInputService.AssignmentRecord;
import com.clinicos.staff.api.EvaluationInputService.AttendanceDay;
import com.clinicos.staff.api.EvaluationInputService.Completion;
import com.clinicos.staff.api.EvaluationInputService.TaskDef;
import com.clinicos.staff.api.TaskFrequency;

/**
 * Pure reimplementation of the legacy monthly scoring algorithm
 * (see docs/roadmap.md appendix). No Spring, no DB, no I/O — every input
 * arrives as plain records so each rule is unit-testable.
 *
 * <p>Dimensions score the mean of their OWN task rates; the manager's 1–5
 * daily ratings never feed a score. Assignments blend into completion, not a
 * separate component (BR-G13). Initiative is the ibda3 component.
 */
public final class ScoringEngine {

    private ScoringEngine() {
    }

    public record EngineInput(
            List<TaskDef> tasks,
            List<Completion> completions,
            List<LocalDate> loggedDates,
            List<AttendanceDay> attendance,
            List<AssignmentRecord> assignments,
            LocalDate asOf,
            BigDecimal volumeActual,
            Map<UUID, LocalDate> lastRunByTask) {
    }

    public record EngineConfig(
            Map<Category, BigDecimal> weights,
            BigDecimal volumeTarget,
            LocalTime shiftStart,
            LocalTime shiftEnd,
            int graceMinutes,
            List<LocalDate> workDays,
            BigDecimal maxIncentive,
            List<ClinicSettingsService.Tier> tiers) {
    }

    public record ComponentScore(
            Category category,
            BigDecimal rawScore,
            BigDecimal weight,
            boolean included,
            BigDecimal overrideFloor) {
    }

    public record EngineResult(
            Map<Category, ComponentScore> components,
            BigDecimal finalScore,
            BigDecimal coverage,
            ClinicSettingsService.Tier tier,
            BigDecimal incentiveAmount) {
    }

    public static EngineResult evaluate(EngineInput in, EngineConfig cfg, Map<Category, BigDecimal> overrides) {
        Map<UUID, BigDecimal> taskRates = new HashMap<>();
        List<Completion> monthCompletions = in.completions();
        for (TaskDef t : in.tasks()) {
            BigDecimal rate = taskRate(t, monthCompletions, in.loggedDates(), in.asOf(), in.lastRunByTask());
            if (rate != null) {
                taskRates.put(t.id(), rate);
            }
        }

        Map<Category, BigDecimal> auto = new LinkedHashMap<>();
        auto.put(Category.COMPLETION, completionScore(taskRates, in));
        auto.put(Category.FANNI, dimRate(taskRates, in.tasks(), "fanni"));
        auto.put(Category.SOLOOKI, dimRate(taskRates, in.tasks(), "solooki"));
        auto.put(Category.IBDA3, initiativeScore(taskRates, in));
        auto.put(Category.VOLUME, volumeScore(in, cfg));
        auto.put(Category.ATTENDANCE, attendanceScore(in, cfg));

        Map<Category, ComponentScore> components = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            BigDecimal weight = cfg.weights().get(category);
            if (weight == null) {
                continue;
            }
            BigDecimal raw = auto.get(category);
            BigDecimal floor = overrides.get(category);
            BigDecimal applied = applyFloor(raw, floor);
            components.put(category, new ComponentScore(category, applied, weight, applied != null, floor));
        }

        List<ComponentScore> assessed = components.values().stream()
                .filter(ComponentScore::included)
                .toList();
        BigDecimal totalWeight = cfg.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal assessedWeight = assessed.stream()
                .map(ComponentScore::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal finalScore = null;
        BigDecimal coverage = null;
        if (!assessed.isEmpty() && assessedWeight.signum() > 0) {
            BigDecimal weighted = BigDecimal.ZERO;
            for (ComponentScore c : assessed) {
                weighted = weighted.add(c.rawScore().multiply(c.weight()));
            }
            finalScore = weighted.divide(assessedWeight, 2, RoundingMode.HALF_UP);
            coverage = assessedWeight.divide(totalWeight, 4, RoundingMode.HALF_UP);
        }

        ClinicSettingsService.Tier tier = tierFor(finalScore, cfg.tiers());
        BigDecimal incentive = tier == null || finalScore == null
                ? BigDecimal.ZERO
                : cfg.maxIncentive().multiply(tier.incentivePct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        return new EngineResult(components, finalScore, coverage, tier, incentive);
    }

    private static BigDecimal applyFloor(BigDecimal raw, BigDecimal floor) {
        if (floor == null) {
            return raw == null ? null : scale2(raw);
        }
        if (raw == null || raw.compareTo(floor) < 0) {
            return scale2(floor);
        }
        return scale2(raw);
    }

    private static BigDecimal taskRate(TaskDef t, List<Completion> completions, List<LocalDate> loggedDates,
            LocalDate asOf, Map<UUID, LocalDate> lastRunByTask) {
        List<LocalDate> done = completions.stream()
                .filter(c -> c.taskDefinitionId().equals(t.id()))
                .map(Completion::workDate)
                .toList();
        return switch (t.frequency()) {
            case "daily" -> {
                if (loggedDates.isEmpty()) {
                    yield null;
                }
                yield pct(done.size(), loggedDates.size());
            }
            case "weekly" -> {
                Set<LocalDate> tracked = weekStarts(loggedDates);
                if (tracked.isEmpty()) {
                    yield null;
                }
                yield pct(weekStarts(done).size(), tracked.size());
            }
            case "monthly" -> done.isEmpty() ? zero() : hundred();
            case "custom" -> {
                if (!done.isEmpty()) {
                    yield hundred();
                }
                LocalDate lastRun = lastRunByTask.get(t.id());
                if (lastRun == null) {
                    yield null;
                }
                int period = TaskFrequency.periodDays(t.frequency(), t.everyN(), t.intervalUnit());
                boolean overdue = java.time.temporal.ChronoUnit.DAYS.between(lastRun, asOf) > period;
                yield overdue ? zero() : null;
            }
            default -> null;
        };
    }

    private static Set<LocalDate> weekStarts(List<LocalDate> dates) {
        return dates.stream()
                .map(d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)))
                .collect(Collectors.toSet());
    }

    private static BigDecimal completionScore(Map<UUID, BigDecimal> taskRates, EngineInput in) {
        BigDecimal taskMean = dimRate(taskRates, in.tasks(), "completion");
        BigDecimal assignmentMean = assignmentMean(in.assignments());
        return meanTwo(taskMean, assignmentMean);
    }

    private static BigDecimal assignmentMean(List<AssignmentRecord> assignments) {
        if (assignments.isEmpty()) {
            return null;
        }
        List<BigDecimal> scores = new ArrayList<>();
        for (AssignmentRecord a : assignments) {
            scores.add(assignmentScore(a));
        }
        return mean(scores);
    }

    private static BigDecimal assignmentScore(AssignmentRecord a) {
        if (!"approved".equals(a.status())) {
            return zero();
        }
        if (a.doneAt() == null) {
            return zero();
        }
        LocalDate done = a.doneAt().toLocalDate();
        if (a.dueDate() == null || !done.isAfter(a.dueDate())) {
            return hundred();
        }
        return bd(50, 0);
    }

    private static BigDecimal initiativeScore(Map<UUID, BigDecimal> taskRates, EngineInput in) {
        long selfApproved = in.assignments().stream()
                .filter(a -> "self".equals(a.proposedBy()) && "approved".equals(a.status()))
                .count();
        BigDecimal initRaw;
        if (selfApproved == 0) {
            initRaw = in.loggedDates().size() >= 3 ? zero() : null;
        } else {
            initRaw = pct(selfApproved, 3);
        }
        BigDecimal ibda3Rate = dimRate(taskRates, in.tasks(), "ibda3");
        return meanTwo(initRaw, ibda3Rate);
    }

    private static BigDecimal dimRate(Map<UUID, BigDecimal> taskRates, List<TaskDef> tasks, String dimension) {
        List<BigDecimal> rates = tasks.stream()
                .filter(t -> dimension.equals(t.dimension()))
                .map(t -> taskRates.get(t.id()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
        if (rates.isEmpty()) {
            return null;
        }
        return mean(rates);
    }

    private static BigDecimal volumeScore(EngineInput in, EngineConfig cfg) {
        if (cfg.volumeTarget() == null) {
            return null;
        }
        List<LocalDate> workDays = cfg.workDays();
        if (workDays.isEmpty()) {
            return null;
        }
        long elapsed = workDays.stream().filter(d -> !d.isAfter(in.asOf())).count();
        if (elapsed == 0) {
            return null;
        }
        BigDecimal pace = cfg.volumeTarget()
                .multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(workDays.size()), 4, RoundingMode.HALF_UP);
        if (in.volumeActual() == null) {
            return null;
        }
        BigDecimal actual = in.volumeActual();
        BigDecimal ratio = actual.divide(pace, 8, RoundingMode.HALF_UP);
        return ratio.compareTo(BigDecimal.ONE) >= 0 ? hundred() : scale2(ratio.multiply(BigDecimal.valueOf(100)));
    }

    private static BigDecimal attendanceScore(EngineInput in, EngineConfig cfg) {
        if (cfg.workDays().isEmpty()) {
            return null;
        }
        Map<LocalDate, AttendanceDay> byDate = in.attendance().stream()
                .collect(Collectors.toMap(AttendanceDay::workDate, d -> d));
        List<BigDecimal> dayScores = new ArrayList<>();
        for (LocalDate workDay : cfg.workDays()) {
            AttendanceDay row = byDate.get(workDay);
            dayScores.add(row == null ? zero() : dayScore(row, cfg));
        }
        return mean(dayScores);
    }

    private static BigDecimal dayScore(AttendanceDay row, EngineConfig cfg) {
        LocalTime shiftStart = cfg.shiftStart();
        LocalTime shiftEnd = cfg.shiftEnd();
        int grace = cfg.graceMinutes();
        BigDecimal arrival;
        if (row.checkInTime() == null) {
            return zero();
        } else if (!row.checkInTime().isBefore(shiftEnd)) {
            return zero();
        } else if (!row.checkInTime().isAfter(shiftStart.plusMinutes(grace))) {
            arrival = hundred();
        } else {
            arrival = bd(50, 0);
        }
        BigDecimal departure;
        if (row.checkOutTime() == null) {
            departure = bd(50, 0);
        } else if (!row.checkOutTime().isBefore(shiftEnd.minusMinutes(grace))) {
            departure = hundred();
        } else {
            departure = bd(50, 0);
        }
        return meanTwo(arrival, departure);
    }

    private static ClinicSettingsService.Tier tierFor(BigDecimal finalScore, List<ClinicSettingsService.Tier> tiers) {
        if (finalScore == null) {
            return null;
        }
        return tiers.stream()
                .filter(t -> finalScore.compareTo(t.minScore()) >= 0)
                .max((a, b) -> a.minScore().compareTo(b.minScore()))
                .orElse(null);
    }

    private static BigDecimal meanTwo(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b == null ? null : scale2(b);
        }
        if (b == null) {
            return scale2(a);
        }
        return scale2(a.add(b).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return scale2(sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal pct(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return scale2(BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP));
    }

    private static BigDecimal zero() {
        return bd(0, 0);
    }

    private static BigDecimal hundred() {
        return bd(100, 0);
    }

    private static BigDecimal bd(long v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}