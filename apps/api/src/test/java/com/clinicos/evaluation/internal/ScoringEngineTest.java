package com.clinicos.evaluation.internal;

import static com.clinicos.clinicconfig.api.ClinicSettingsService.Category.COMPLETION;
import static com.clinicos.clinicconfig.api.ClinicSettingsService.Category.ATTENDANCE;
import static com.clinicos.clinicconfig.api.ClinicSettingsService.Category.FANNI;
import static com.clinicos.clinicconfig.api.ClinicSettingsService.Category.IBDA3;
import static com.clinicos.clinicconfig.api.ClinicSettingsService.Category.SOLOOKI;
import static com.clinicos.clinicconfig.api.ClinicSettingsService.Category.VOLUME;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.evaluation.internal.ScoringEngine.ComponentScore;
import com.clinicos.evaluation.internal.ScoringEngine.EngineConfig;
import com.clinicos.evaluation.internal.ScoringEngine.EngineInput;
import com.clinicos.evaluation.internal.ScoringEngine.EngineResult;
import com.clinicos.staff.api.EvaluationInputService.AssignmentRecord;
import com.clinicos.staff.api.EvaluationInputService.AttendanceDay;
import com.clinicos.staff.api.EvaluationInputService.Completion;
import com.clinicos.staff.api.EvaluationInputService.TaskDef;

/**
 * BR-G06/G07/G13/G14/G15/G19/G20/G29 — each scoring rule from the legacy
 * algorithm lives here as a named test. Pure function: no Spring, no DB.
 */
class ScoringEngineTest {

    private static final LocalDate M_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate M_END = LocalDate.of(2026, 7, 31);
    private static final BigDecimal MAX_INCENTIVE = new BigDecimal("1000");

    private static final List<ClinicSettingsService.Tier> TIERS = List.of(
            new ClinicSettingsService.Tier("ممتاز", new BigDecimal("90"), new BigDecimal("100")),
            new ClinicSettingsService.Tier("جيد جداً", new BigDecimal("75"), new BigDecimal("75")),
            new ClinicSettingsService.Tier("جيد", new BigDecimal("60"), new BigDecimal("50")),
            new ClinicSettingsService.Tier("يحتاج تطوير", BigDecimal.ZERO, BigDecimal.ZERO));

    private static Map<Category, BigDecimal> defaultWeights() {
        Map<Category, BigDecimal> m = new LinkedHashMap<>();
        m.put(COMPLETION, bd(18));
        m.put(FANNI, bd(18));
        m.put(SOLOOKI, bd(12));
        m.put(IBDA3, bd(22));
        m.put(VOLUME, bd(18));
        m.put(ATTENDANCE, bd(12));
        return m;
    }

    private static BigDecimal bd(double v) {
        return new BigDecimal(String.valueOf(v));
    }

    private static TaskDef task(String name, String dim, String freq, Integer everyN, String unit) {
        return new TaskDef(UUID.nameUUIDFromBytes(name.getBytes()), name, dim, freq, everyN, unit,
                LocalDateTime.of(M_START.minusDays(1), LocalTime.NOON));
    }

    private static Completion done(String taskName, LocalDate date) {
        return new Completion(UUID.nameUUIDFromBytes(taskName.getBytes()), date);
    }

    private static AssignmentRecord assignment(String status, String proposer, LocalDate due,
            LocalDate doneOn) {
        return new AssignmentRecord(UUID.randomUUID(), due, status,
                LocalDateTime.of(M_START, LocalTime.NOON), proposer,
                doneOn == null ? null : doneOn.atTime(LocalTime.NOON).atOffset(OffsetDateTime.now().getOffset()));
    }

    private static EngineInput input(List<TaskDef> tasks, List<Completion> completions,
            List<LocalDate> logged, List<AttendanceDay> attendance, List<AssignmentRecord> assignments) {
        return new EngineInput(tasks, completions, logged, attendance, assignments, M_END, null, Map.of());
    }

    private static EngineInput input(List<TaskDef> tasks, List<Completion> completions,
            List<LocalDate> logged, List<AttendanceDay> attendance, List<AssignmentRecord> assignments,
            LocalDate asOf, BigDecimal volumeActual) {
        return new EngineInput(tasks, completions, logged, attendance, assignments, asOf, volumeActual, Map.of());
    }

    private static EngineConfig config(BigDecimal volumeTarget, List<LocalDate> workDays) {
        return new EngineConfig(defaultWeights(), volumeTarget,
                LocalTime.of(9, 0), LocalTime.of(17, 0), 15, workDays, MAX_INCENTIVE, TIERS);
    }

    private static EngineConfig config(List<LocalDate> workDays) {
        return config(new BigDecimal("20000"), workDays);
    }

    private static EngineConfig config() {
        return config(new BigDecimal("20000"), List.of(d(1), d(2), d(3)));
    }

    private static ComponentScore component(EngineResult result, Category cat) {
        return result.components().get(cat);
    }

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 7, day);
    }

    // daily: done-days ÷ logged-days
    @Test
    void dailyTaskRate_doneThreeOfFourLoggedDays_scores75() {
        TaskDef t = task("تنظيف", "fanni", "daily", null, null);
        List<LocalDate> logged = List.of(d(1), d(2), d(3), d(4));
        List<Completion> completions = List.of(done("تنظيف", d(1)), done("تنظيف", d(2)), done("تنظيف", d(3)));

        EngineResult result = ScoringEngine.evaluate(input(List.of(t), completions, logged, List.of(), List.of()), config(), Map.of());

        assertThat(component(result, FANNI).rawScore()).isEqualByComparingTo("75.00");
    }

    @Test
    void dailyTask_noLoggedDays_rateNull() {
        TaskDef t = task("تنظيف", "fanni", "daily", null, null);

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), List.of(), List.of(), List.of(), List.of()), config(), Map.of());

        assertThat(component(result, FANNI).included()).isFalse();
        assertThat(component(result, FANNI).rawScore()).isNull();
    }

    // weekly: distinct Saturday-weeks done ÷ weeks tracked
    @Test
    void weeklyTask_twoDoneSaturdaysOfFourTracked_scores50() {
        TaskDef t = task("جولة", "completion", "weekly", null, null);
        List<LocalDate> logged = List.of(d(4), d(11), d(18), d(25));
        List<Completion> completions = List.of(done("جولة", d(4)), done("جولة", d(18)));

        EngineResult result = ScoringEngine.evaluate(input(List.of(t), completions, logged, List.of(), List.of()), config(), Map.of());

        assertThat(component(result, COMPLETION).rawScore()).isEqualByComparingTo("50.00");
    }

    // monthly: 100 once, else 0
    @Test
    void monthlyTask_doneOnce_scores100() {
        TaskDef t = task("مراجعة شهرية", "solooki", "monthly", null, null);
        List<LocalDate> logged = List.of(d(1), d(8));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), List.of(done("مراجعة شهرية", d(5))), logged, List.of(), List.of()),
                config(), Map.of());

        assertThat(component(result, SOLOOKI).rawScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void monthlyTask_notDone_scores0() {
        TaskDef t = task("مراجعة شهرية", "solooki", "monthly", null, null);

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), List.of(), List.of(d(1)), List.of(), List.of()), config(), Map.of());

        assertThat(component(result, SOLOOKI).rawScore()).isEqualByComparingTo("0.00");
    }

    // custom interval
    @Test
    void customTask_done_scores100() {
        TaskDef t = task("صيانة دورية", "fanni", "custom", 7, "day");
        List<LocalDate> logged = List.of(d(10));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), List.of(done("صيانة دورية", d(10))), logged, List.of(), List.of()),
                config(), Map.of());

        assertThat(component(result, FANNI).rawScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void customTask_notDue_nullNotZero() {
        TaskDef t = task("صيانة دورية", "fanni", "custom", 7, "day");
        List<LocalDate> logged = List.of(d(1));
        Map<UUID, LocalDate> recent = Map.of(t.id(), LocalDate.of(2026, 7, 25));

        EngineInput in = new EngineInput(List.of(t), List.of(), logged, List.of(), List.of(), M_END, null, recent);
        EngineResult result = ScoringEngine.evaluate(in, config(), Map.of());

        // last run July 25 is within 7 days of July 31 → not yet due → null
        assertThat(component(result, FANNI).included()).isFalse();
        assertThat(component(result, FANNI).rawScore()).isNull();
    }

    @Test
    void customTask_neverRun_nullNotZero() {
        TaskDef t = task("صيانة دورية", "fanni", "custom", 7, "day");
        List<LocalDate> logged = List.of(d(1));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), List.of(), logged, List.of(), List.of()), config(), Map.of());

        assertThat(component(result, FANNI).included()).isFalse();
        assertThat(component(result, FANNI).rawScore()).isNull();
    }

    @Test
    void customTask_noCompletionInMonthButLastRunStale_scores0() {
        TaskDef t = task("صيانة دورية", "fanni", "custom", 7, "day");
        List<LocalDate> logged = List.of(d(1));

        Map<UUID, LocalDate> stale = Map.of(t.id(), LocalDate.of(2026, 6, 1));
        EngineInput in = new EngineInput(List.of(t), List.of(), logged, List.of(), List.of(), M_END, null, stale);

        EngineResult result = ScoringEngine.evaluate(in, config(), Map.of());

        // last run June 1 → older than 7 days on July 31 → overdue → 0
        assertThat(component(result, FANNI).included()).isTrue();
        assertThat(component(result, FANNI).rawScore()).isEqualByComparingTo("0.00");
    }

    // byDim: a dimension scores the mean of ITS tasks, ratings never leak in
    @Test
    void byDim_fanniOnlyUsesFanniTasks_otherDimensionDoesNotRaiseIt() {
        TaskDef fanniT = task("تنظيف", "fanni", "daily", null, null);
        TaskDef solookiT = task("استقبال", "solooki", "daily", null, null);
        List<LocalDate> logged = List.of(d(1));
        List<Completion> completions = List.of(done("استقبال", d(1)));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(fanniT, solookiT), completions, logged, List.of(), List.of()), config(), Map.of());

        assertThat(component(result, FANNI).rawScore()).isEqualByComparingTo("0.00");
        assertThat(component(result, SOLOOKI).rawScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void byDim_meansNonNullTaskRates() {
        TaskDef a = task("أ", "fanni", "daily", null, null);
        TaskDef b = task("ب", "fanni", "custom", 7, "day");
        List<LocalDate> logged = List.of(d(1));
        List<Completion> completions = List.of(done("أ", d(1)));   // أ done=100, ب never run = null

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(a, b), completions, logged, List.of(), List.of()), config(), Map.of());

        assertThat(component(result, FANNI).rawScore()).isEqualByComparingTo("100.00");
    }

    // BR-G13: assignments blend into completion
    @Test
    void assignmentApprovedOnTime_scores100_andBlendsIntoCompletion() {
        TaskDef t = task("تنظيف", "completion", "daily", null, null);
        List<LocalDate> logged = List.of(d(1));
        List<AssignmentRecord> assignments = List.of(assignment("approved", "manager", d(10), d(9)));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), List.of(done("تنظيف", d(1))), logged, List.of(), assignments), config(), Map.of());

        assertThat(component(result, COMPLETION).rawScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void assignmentLate_scores50() {
        List<AssignmentRecord> assignments = List.of(assignment("approved", "manager", d(5), d(9)));

        EngineResult result = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(d(1)), List.of(), assignments), config(), Map.of());

        assertThat(component(result, COMPLETION).rawScore()).isEqualByComparingTo("50.00");
    }

    @Test
    void assignmentPendingOrNotDone_scores0() {
        List<AssignmentRecord> assignments = List.of(assignment("pending", "manager", d(10), null),
                assignment("approved", "manager", d(10), null));

        EngineResult result = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(d(1)), List.of(), assignments), config(), Map.of());

        BigDecimal score = result.components().get(COMPLETION).rawScore();
        assertThat(score.stripTrailingZeros()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // initiative: min(approved self ∕ 3 × 100, 100), averaged with ibda3
    @Test
    void initiative_threeApprovedSelfProposals_capsAt100() {
        List<LocalDate> logged = List.of(d(1), d(2), d(3));
        List<AssignmentRecord> assignments = List.of(
                assignment("approved", "self", d(5), d(4)),
                assignment("approved", "self", d(6), d(5)),
                assignment("approved", "self", d(7), d(6)));
        TaskDef ibda3T = task("ابتكار", "ibda3", "daily", null, null);
        List<Completion> completions = List.of(done("ابتكار", d(1)), done("ابتكار", d(2)), done("ابتكار", d(3)));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(ibda3T), completions, logged, List.of(), assignments), config(), Map.of());

        // min(3/3*100,100)=100, ibda3 dim=100 → mean 100
        assertThat(component(result, IBDA3).rawScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void initiative_zeroSelfProposalsAfterThreeLoggedDays_scores0() {
        List<LocalDate> logged = List.of(d(1), d(2), d(3));
        TaskDef ibda3T = task("ابتكار", "ibda3", "daily", null, null);
        List<Completion> completions = List.of(done("ابتكار", d(1)), done("ابتكار", d(2)), done("ابتكار", d(3)));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(ibda3T), completions, logged, List.of(), List.of()), config(), Map.of());

        // initiativeRaw 0, ibda3 dim 100 → mean 50
        assertThat(component(result, IBDA3).rawScore()).isEqualByComparingTo("50.00");
    }

    // BR-G29: volume is pace-adjusted to elapsed workdays, capped at 100
    @Test
    void volume_paceAdjustedTarget_cappedAt100() {
        List<LocalDate> workDays = List.of(d(1), d(2), d(3), d(4), d(5));
        EngineConfig cfg = config(new BigDecimal("20000"), workDays);
        // asOf d(3) → elapsed 3 of 5 workdays → paceTarget 12000
        EngineResult atTarget = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), List.of(), List.of(), d(3), bd(12000)), cfg, Map.of());
        EngineResult over = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), List.of(), List.of(), d(3), bd(20000)), cfg, Map.of());
        EngineResult below = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), List.of(), List.of(), d(3), bd(6000)), cfg, Map.of());

        assertThat(component(atTarget, VOLUME).rawScore()).isEqualByComparingTo("100.00");
        assertThat(component(over, VOLUME).rawScore()).isEqualByComparingTo("100.00");
        assertThat(component(below, VOLUME).rawScore()).isEqualByComparingTo("50.00");
    }

    @Test
    void volume_noTargetConfigured_rateNull() {
        EngineConfig cfg = config(null, List.of(d(1)));

        EngineResult result = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), List.of(), List.of(), d(1), bd(5000)), cfg, Map.of());

        assertThat(component(result, VOLUME).included()).isFalse();
        assertThat(component(result, VOLUME).rawScore()).isNull();
    }

    // BR-G20: attendance per workday, mean across working days
    @Test
    void attendance_ontimeLateAndAbsentDays_meanAcrossWorkdays() {
        List<LocalDate> workDays = List.of(d(1), d(2), d(3));
        List<AttendanceDay> rows = List.of(
                new AttendanceDay(d(1), LocalTime.of(9, 0), LocalTime.of(17, 0)),
                new AttendanceDay(d(2), LocalTime.of(9, 30), LocalTime.of(17, 0)));
        // d(3): absent on a working day → 0

        EngineResult result = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), rows, List.of()), config(workDays), Map.of());

        // 100, (50+100)/2=75, 0 → 58.33
        assertThat(component(result, ATTENDANCE).rawScore()).isEqualByComparingTo("58.33");
    }

    @Test
    void attendance_checkInAfterShiftEnd_zerosTheDay() {
        List<LocalDate> workDays = List.of(d(1));
        List<AttendanceDay> rows = List.of(new AttendanceDay(d(1), LocalTime.of(18, 0), LocalTime.of(19, 0)));

        EngineResult result = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), rows, List.of()), config(workDays), Map.of());

        assertThat(component(result, ATTENDANCE).rawScore()).isEqualByComparingTo("0.00");
    }

    @Test
    void attendance_missingCheckOut_scores50ForDeparture() {
        List<LocalDate> workDays = List.of(d(1));
        List<AttendanceDay> rows = List.of(new AttendanceDay(d(1), LocalTime.of(9, 0), null));

        EngineResult result = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), rows, List.of()), config(workDays), Map.of());

        // arrival 100, departure 50 → 75
        assertThat(component(result, ATTENDANCE).rawScore()).isEqualByComparingTo("75.00");
    }

    // BR-G06: override is a floor, doesn't lower, and supplies a null auto
    @Test
    void override_floorLowersNever_keepsHigherScore() {
        TaskDef t = task("تنظيف", "fanni", "daily", null, null);
        List<LocalDate> logged = List.of(d(1));
        List<Completion> completions = List.of(done("تنظيف", d(1)));

        EngineResult kept = ScoringEngine.evaluate(
                input(List.of(t), completions, logged, List.of(), List.of()),
                config(), Map.of(Category.FANNI, bd(30)));

        assertThat(component(kept, FANNI).rawScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void override_floorRaised_scoreAndFloorExposed() {
        TaskDef t = task("تنظيف", "fanni", "daily", null, null);
        List<LocalDate> logged = List.of(d(1));
        List<Completion> completions = List.of(); // not done → 0

        EngineResult raised = ScoringEngine.evaluate(
                input(List.of(t), completions, logged, List.of(), List.of()),
                config(), Map.of(Category.FANNI, bd(60)));

        assertThat(component(raised, FANNI).rawScore()).isEqualByComparingTo("60.00");
        assertThat(component(raised, FANNI).overrideFloor()).isEqualByComparingTo("60.00");
    }

    @Test
    void override_nullRaw_suppliedByFloor() {
        EngineConfig cfg = config(null, List.of(d(1))); // volume auto = null

        EngineResult result = ScoringEngine.evaluate(
                input(noTasks(), List.of(), List.of(), List.of(), List.of(), d(1), bd(5000)),
                cfg, Map.of(Category.VOLUME, bd(40)));

        assertThat(component(result, VOLUME).included()).isTrue();
        assertThat(component(result, VOLUME).rawScore()).isEqualByComparingTo("40.00");
    }

    // BR-G14/G15: final = Σ v·w ÷ Σ w over assessed only; null component never zeroed
    @Test
    void finalScore_renormalisesByAssessedWeights_only() {
        TaskDef t = task("جولة", "completion", "daily", null, null);
        List<LocalDate> logged = List.of(d(1), d(2), d(3));
        List<Completion> completions = List.of(done("جولة", d(1)), done("جولة", d(2)));
        List<LocalDate> workDays = List.of(d(1));
        List<AttendanceDay> rows = List.of(new AttendanceDay(d(1), LocalTime.of(9, 0), LocalTime.of(17, 0)));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), completions, logged, rows, List.of()),
                config(workDays), Map.of());

        // assessed = completion 66.67(w18, component rounded to 2dp) + attendance 100(w12)
        // + ibda3 0(w22, zero self-proposals after 3 logged days); fanni/solooki null
        // excluded, volume null excluded (no actual data) → (1200.06+1200)/52 = 46.16
        BigDecimal expected = bd(66.67).multiply(bd(18)).add(bd(100).multiply(bd(12)))
                .divide(bd(52), 2, RoundingMode.HALF_UP);
        assertThat(result.finalScore()).isEqualByComparingTo(expected);
        assertThat(result.coverage()).isEqualByComparingTo("0.52");
    }

    // BR-G07: incentive = maxIncentive × tier.pct
    @Test
    void incentive_mapsTierByFinalScore() {
        TaskDef t = task("تنظيف", "fanni", "daily", null, null);
        List<LocalDate> logged = List.of(d(1));
        List<Completion> completions = List.of(done("تنظيف", d(1)));
        List<LocalDate> workDays = List.of(d(1));
        List<AttendanceDay> rows = List.of(new AttendanceDay(d(1), LocalTime.of(9, 0), LocalTime.of(17, 0)));

        EngineResult result = ScoringEngine.evaluate(
                input(List.of(t), completions, logged, rows, List.of()),
                config(workDays), Map.of());

        // fanni 100 + attendance 100 → (100*18+100*12)/30 = 100 → tier ممتاز 100%
        assertThat(result.tier().name()).isEqualTo("ممتاز");
        assertThat(result.incentiveAmount()).isEqualByComparingTo("1000.00");
    }

    private static List<TaskDef> noTasks() {
        return List.of();
    }
}