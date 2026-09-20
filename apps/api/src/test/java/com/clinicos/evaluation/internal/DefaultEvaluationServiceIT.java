package com.clinicos.evaluation.internal;

import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.EvaluationComponent.EVALUATION_COMPONENT;
import static com.clinicos.shared.jooq.tables.EvaluationSnapshot.EVALUATION_SNAPSHOT;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.OperationsVolume.OPERATIONS_VOLUME;
import static com.clinicos.shared.jooq.tables.SelfCheck.SELF_CHECK;
import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.evaluation.api.EvaluationService;
import com.clinicos.evaluation.api.EvaluationService.ComponentScore;
import com.clinicos.evaluation.api.EvaluationService.Gamification;
import com.clinicos.evaluation.api.EvaluationService.MonthlyEvaluation;
import com.clinicos.evaluation.api.EvaluationService.TeamScore;
import com.clinicos.evaluation.api.EvaluationService.VolumePace;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.enums.TaskReviewStatus;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.EmployeeService;

@SpringBootTest(classes = Application.class)
class DefaultEvaluationServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private DailyWorkService dailyWorkService;

    @Autowired
    private ClinicSettingsService clinicSettingsService;

    @Autowired
    private WorkCalendarService workCalendarService;

    @Autowired
    private GamificationService gamificationService;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID clinicA;
    private UUID clinicB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (Connection conn = superuser()) {
            clinicA = TestFixtures.insertClinic(conn);
            clinicB = TestFixtures.insertClinic(conn);
        }
    }

    private void seedWeekdays(UUID clinicId) {
        TenantContext.set(clinicId);
        workCalendarService.setWorkingWeekdays(clinicId, List.of(1, 2, 3, 4, 5, 6, 7));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void closedMonth_fullGreen_persistsSnapshotAndReturnsFullScore() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth june = YearMonth.of(2026, 6);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", june.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        for (LocalDate day = june.atDay(1); !day.isAfter(june.atEndOfMonth()); day = day.plusDays(1)) {
            seedWorkday(clinicA, employeeId, taskId, day);
        }
        seedVolume(clinicA, june, "20000");

        MonthlyEvaluation evaluation = evaluationService.evaluate(clinicA, employeeId, june);

        assertThat(evaluation.finalScore()).isEqualByComparingTo("68.57");
        assertThat(evaluation.coverage()).isEqualByComparingTo("0.70");
        assertThat(evaluation.tierName()).isEqualTo("جيد");
        assertThat(evaluation.incentiveAmount()).isEqualByComparingTo("500.00");
        assertThat(evaluation.basePay()).isEqualByComparingTo("5000.00");
        assertThat(evaluation.totalPay()).isEqualByComparingTo("5500.00");
        assertThat(evaluation.daysLogged()).isEqualTo(30);
        assertThat(evaluation.frozen()).isTrue();
        assertThat(evaluation.components()).hasSize(6);
        assertThat(component(evaluation, "fanni").rawScore()).isEqualByComparingTo("100.00");
        assertThat(component(evaluation, "attendance").rawScore()).isEqualByComparingTo("100.00");
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(componentCount()).isEqualTo(6);
    }

    @Test
    void closedMonth_reevaluate_rehydratesFrozenSnapshot() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth june = YearMonth.of(2026, 6);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", june.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        for (LocalDate day = june.atDay(1); !day.isAfter(june.atEndOfMonth()); day = day.plusDays(1)) {
            seedWorkday(clinicA, employeeId, taskId, day);
        }
        seedVolume(clinicA, june, "20000");

        MonthlyEvaluation first = evaluationService.evaluate(clinicA, employeeId, june);
        MonthlyEvaluation again = evaluationService.evaluate(clinicA, employeeId, june);

        assertThat(again.finalScore()).isEqualByComparingTo("68.57");
        assertThat(again.frozen()).isTrue();
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(componentCount()).isEqualTo(6);
    }

    @Test
    void closedMonth_unlockThenOverrideAndReevaluate_replacesSnapshotAndFloorsComponent() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth june = YearMonth.of(2026, 6);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", june.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        // full month attendance grid, but only 2 of 30 workdays completed → fanni auto ≈ 6.67
        LocalDate skipA = june.atDay(4);
        LocalDate skipB = june.atDay(11);
        for (LocalDate day = june.atDay(1); !day.isAfter(june.atEndOfMonth()); day = day.plusDays(1)) {
            seedAttendanceOnly(clinicA, employeeId, day);
        }
        seedCompletion(clinicA, employeeId, taskId, skipA);
        seedCompletion(clinicA, employeeId, taskId, skipB);

        MonthlyEvaluation before = evaluationService.evaluate(clinicA, employeeId, june);
        assertThat(component(before, "fanni").rawScore()).isEqualByComparingTo("6.67");

        evaluationService.setOverride(clinicA, employeeId, june, ClinicSettingsService.Category.FANNI,
                new BigDecimal("70"), membershipId(employeeId));
        evaluationService.unlock(clinicA, employeeId, june, membershipId(employeeId));

        MonthlyEvaluation after = evaluationService.evaluate(clinicA, employeeId, june);

        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(componentCount()).isEqualTo(6);
        assertThat(component(after, "fanni").rawScore()).isEqualByComparingTo("70.00");
        assertThat(component(after, "fanni").overrideFloor()).isEqualByComparingTo("70.00");
        assertThat(after.frozen()).isTrue();
    }

    @Test
    void currentMonth_approvingPendingCompletion_changesScoreOnRescore() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth now = YearMonth.now();
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", now.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        LocalDate workDate = LocalDate.now();
        seedAttendanceOnly(clinicA, employeeId, workDate);
        UUID recordId = seedPendingCompletion(clinicA, employeeId, taskId, workDate);

        MonthlyEvaluation before = evaluationService.evaluate(clinicA, employeeId, now);
        assertThat(component(before, "fanni").rawScore()).isEqualByComparingTo("0.00");

        dailyWorkService.approveReview(clinicA, recordId, taskId, membershipId(employeeId));
        MonthlyEvaluation after = evaluationService.evaluate(clinicA, employeeId, now);

        assertThat(component(after, "fanni").rawScore()).isEqualByComparingTo("100.00");
    }

    @Test
    void currentMonth_liveEvaluation_noSnapshotPersisted() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth now = YearMonth.now();
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", now.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        seedWorkday(clinicA, employeeId, taskId, LocalDate.now());

        MonthlyEvaluation evaluation = evaluationService.evaluate(clinicA, employeeId, now);

        assertThat(evaluation.frozen()).isFalse();
        assertThat(evaluation.finalScore()).isNotNull();
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void gamification_computesRealStreakAndBadgesFromAttendanceAndCompletions() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth june = YearMonth.of(2026, 6);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", june.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        LocalDate asOf = june.atEndOfMonth();
        seedWorkday(clinicA, employeeId, taskId, asOf);
        seedWorkday(clinicA, employeeId, taskId, asOf.minusDays(1));
        seedWorkday(clinicA, employeeId, taskId, asOf.minusDays(2));
        gamificationService.updateGoal(clinicA, 1, "إنجاز أسبوعي", 2);
        gamificationService.updateThreshold(clinicA, "بطل الأسبوع", 3);

        Gamification result = evaluationService.gamification(clinicA, employeeId, june);

        assertThat(result.streak()).isEqualTo(3);
        assertThat(result.goals()).hasSize(1);
        assertThat(result.goals().get(0).current()).isEqualTo(3);
        assertThat(result.earnedBadges()).contains("بطل الأسبوع");
    }

    @Test
    void crossTenant_evaluateWithOtherClinicsEmployee_returnsNoData() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth june = YearMonth.of(2026, 6);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", june.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        for (LocalDate day = june.atDay(1); !day.isAfter(june.atEndOfMonth()); day = day.plusDays(1)) {
            seedWorkday(clinicA, employeeId, taskId, day);
        }
        MonthlyEvaluation ownTenant = evaluationService.evaluate(clinicA, employeeId, june);
        assertThat(ownTenant).isNotNull();

        TenantContext.set(clinicB);
        MonthlyEvaluation otherTenant = evaluationService.evaluate(clinicB, employeeId, june);

        assertThat(otherTenant).isNull();
    }

    @Test
    void closedMonth_concurrentFirstReads_onlyOneWinsSnapshotRace() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth june = YearMonth.of(2026, 6);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", june.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        for (LocalDate day = june.atDay(1); !day.isAfter(june.atEndOfMonth()); day = day.plusDays(1)) {
            seedWorkday(clinicA, employeeId, taskId, day);
        }
        seedVolume(clinicA, june, "20000");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<MonthlyEvaluation>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                TenantContext.set(clinicA);
                ready.countDown();
                go.await();
                try {
                    return evaluationService.evaluate(clinicA, employeeId, june);
                } finally {
                    TenantContext.clear();
                }
            });
        }
        List<Future<MonthlyEvaluation>> futures = tasks.stream().map(pool::submit).toList();
        ready.await();
        go.countDown();
        pool.shutdown();

        long succeeded = 0;
        long conflicted = 0;
        for (Future<MonthlyEvaluation> f : futures) {
            try {
                MonthlyEvaluation result = f.get(10, TimeUnit.SECONDS);
                assertThat(result.finalScore()).isEqualByComparingTo("68.57");
                succeeded++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(EvaluationService.EvaluationConflictException.class);
                conflicted++;
            }
        }

        assertThat(succeeded).isGreaterThan(0);
        assertThat(succeeded + conflicted).isEqualTo(threads);
        assertThat(snapshotCount()).isEqualTo(1);
    }

    @Test
    void recordVolume_persistsAndUpserts() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth now = YearMonth.now();
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID recorderId = membershipId(employeeId);

        evaluationService.recordVolume(clinicA, now, new BigDecimal("7000"), recorderId);
        assertThat(evaluationService.volume(clinicA, now)).isEqualByComparingTo("7000");

        evaluationService.recordVolume(clinicA, now, new BigDecimal("9000"), recorderId);
        assertThat(evaluationService.volume(clinicA, now)).isEqualByComparingTo("9000");
    }

    @Test
    void recordVolume_negativeAmountRejectedWithoutPersisting() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth now = YearMonth.now();
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID recorderId = membershipId(employeeId);

        assertThatThrownBy(() -> evaluationService.recordVolume(clinicA, now, new BigDecimal("-100"), recorderId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(evaluationService.volume(clinicA, now)).isNull();
    }

    @Test
    void volumePace_computesPaceAdjustedTargetForElapsedWorkdays() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth now = YearMonth.now();
        seedVolume(clinicA, now, "5000");

        VolumePace pace = evaluationService.volumePace(clinicA, now);

        int elapsed = LocalDate.now().getDayOfMonth();
        int total = now.lengthOfMonth();
        BigDecimal expectedPaceTarget = new BigDecimal("20000").multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        assertThat(pace.monthlyTarget()).isEqualByComparingTo("20000");
        assertThat(pace.actual()).isEqualByComparingTo("5000");
        assertThat(pace.workingDaysElapsed()).isEqualTo(elapsed);
        assertThat(pace.workingDaysInMonth()).isEqualTo(total);
        assertThat(pace.paceTarget()).isEqualByComparingTo(expectedPaceTarget);
    }

    @Test
    void teamScores_populatedTeamExcludesOwnerAndIncludesScoredEmployee() throws Exception {
        TenantContext.set(clinicA);
        seedWeekdays(clinicA);
        YearMonth june = YearMonth.of(2026, 6);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTaskAt(clinicA, "assistant", "تنظيف", june.atDay(1).minusDays(1), "fanni", TaskFrequency.daily);
        for (LocalDate day = june.atDay(1); !day.isAfter(june.atEndOfMonth()); day = day.plusDays(1)) {
            seedWorkday(clinicA, employeeId, taskId, day);
        }
        seedVolume(clinicA, june, "20000");

        List<TeamScore> team = evaluationService.teamScores(clinicA, june);

        assertThat(team).hasSize(1);
        TeamScore score = team.get(0);
        assertThat(score.employeeId()).isEqualTo(employeeId);
        assertThat(score.roleCode()).isEqualTo("assistant");
        assertThat(score.finalScore()).isEqualByComparingTo("68.57");
        assertThat(score.tierName()).isEqualTo("جيد");
        assertThat(score.daysLogged()).isEqualTo(30);
    }

    private ComponentScore component(MonthlyEvaluation evaluation, String code) {
        return evaluation.components().stream()
                .filter(c -> c.category().code().equals(code))
                .findFirst().orElseThrow();
    }

    private long snapshotCount() {
        return transactionTemplate.execute(s -> dsl.fetchCount(EVALUATION_SNAPSHOT));
    }

    private long componentCount() {
        return transactionTemplate.execute(s -> dsl.fetchCount(EVALUATION_COMPONENT));
    }

    private UUID createEmployee(String name) {
        return employeeService.create(clinicA,
                new EmployeeService.EmployeeRequest(name, new BigDecimal("5000"), new BigDecimal("1000"),
                        LocalTime.of(9, 0), LocalTime.of(17, 0), false, null)).id();
    }

    private void seedWorkday(UUID clinicId, UUID employeeId, UUID taskId, LocalDate workDate) throws Exception {
        seedAttendanceOnly(clinicId, employeeId, workDate);
        seedCompletion(clinicId, employeeId, taskId, workDate);
    }

    private void seedAttendanceOnly(UUID clinicId, UUID employeeId, LocalDate workDate) throws Exception {
        seedSelfCheck(clinicId, employeeId, workDate);
        seedRecord(clinicId, employeeId, workDate);
    }

    private void seedRecord(UUID clinicId, UUID employeeId, LocalDate workDate) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(DAILY_RECORD)
                    .set(DAILY_RECORD.ID, UUID.randomUUID())
                    .set(DAILY_RECORD.CLINIC_ID, clinicId)
                    .set(DAILY_RECORD.EMPLOYEE_ID, employeeId)
                    .set(DAILY_RECORD.WORK_DATE, workDate)
                    .execute();
        }
    }

    private void seedCompletion(UUID clinicId, UUID employeeId, UUID taskId, LocalDate workDate)
            throws Exception {
        try (Connection conn = superuser()) {
            DSLContext root = DSL.using(conn, SQLDialect.POSTGRES);
            UUID recordId = root.select(DAILY_RECORD.ID)
                    .from(DAILY_RECORD)
                    .where(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                    .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                    .and(DAILY_RECORD.WORK_DATE.eq(workDate))
                    .fetchOne(DAILY_RECORD.ID);
            root.insertInto(DAILY_TASK_COMPLETION)
                    .set(DAILY_TASK_COMPLETION.DAILY_RECORD_ID, recordId)
                    .set(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, taskId)
                    .set(DAILY_TASK_COMPLETION.DONE, true)
                    .set(DAILY_TASK_COMPLETION.REVIEW_STATUS, TaskReviewStatus.approved)
                    .execute();
        }
    }

    private UUID seedPendingCompletion(UUID clinicId, UUID employeeId, UUID taskId, LocalDate workDate)
            throws Exception {
        try (Connection conn = superuser()) {
            DSLContext root = DSL.using(conn, SQLDialect.POSTGRES);
            UUID recordId = root.select(DAILY_RECORD.ID)
                    .from(DAILY_RECORD)
                    .where(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                    .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                    .and(DAILY_RECORD.WORK_DATE.eq(workDate))
                    .fetchOne(DAILY_RECORD.ID);
            root.insertInto(DAILY_TASK_COMPLETION)
                    .set(DAILY_TASK_COMPLETION.DAILY_RECORD_ID, recordId)
                    .set(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, taskId)
                    .set(DAILY_TASK_COMPLETION.DONE, true)
                    .set(DAILY_TASK_COMPLETION.REVIEW_STATUS, TaskReviewStatus.pending)
                    .execute();
            return recordId;
        }
    }

    private void seedSelfCheck(UUID clinicId, UUID employeeId, LocalDate workDate) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(SELF_CHECK, SELF_CHECK.CLINIC_ID, SELF_CHECK.EMPLOYEE_ID,
                            SELF_CHECK.WORK_DATE, SELF_CHECK.CHECKED_IN_AT, SELF_CHECK.CHECKED_OUT_AT)
                    .values(clinicId, employeeId, workDate,
                            workDate.atTime(8, 55).atOffset(OffsetDateTime.now().getOffset()),
                            workDate.atTime(17, 0).atOffset(OffsetDateTime.now().getOffset()))
                    .execute();
        }
    }

    private void seedVolume(UUID clinicId, YearMonth month, String amount) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(OPERATIONS_VOLUME, OPERATIONS_VOLUME.CLINIC_ID,
                            OPERATIONS_VOLUME.PERIOD_MONTH, OPERATIONS_VOLUME.AMOUNT)
                    .values(clinicId, month.atDay(1), new BigDecimal(amount))
                    .execute();
        }
    }

    private UUID membershipId(UUID employeeId) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .select(MEMBERSHIP.ID)
                    .from(MEMBERSHIP)
                    .where(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                    .fetchOne(MEMBERSHIP.ID);
        }
    }

    private UUID seedTaskAt(UUID clinicId, String roleCode, String name, LocalDate createdAt, String dimension,
            TaskFrequency frequency) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(TASK_DEFINITION)
                    .set(TASK_DEFINITION.CLINIC_ID, clinicId)
                    .set(TASK_DEFINITION.NAME, name)
                    .set(TASK_DEFINITION.DIMENSION, TaskDimension.valueOf(dimension))
                    .set(TASK_DEFINITION.FREQUENCY, frequency)
                    .set(TASK_DEFINITION.REQUIRES_PHOTO, false)
                    .set(TASK_DEFINITION.DISPLAY_ORDER, 1)
                    .set(TASK_DEFINITION.ROLE_CODE, roleCode)
                    .set(TASK_DEFINITION.CREATED_AT, createdAt.atStartOfDay().atOffset(OffsetDateTime.now().getOffset()))
                    .returningResult(TASK_DEFINITION.ID)
                    .fetchOne(TASK_DEFINITION.ID);
        }
    }

    private void linkEmployeeToRole(UUID employeeId, String roleCode) throws Exception {
        try (Connection conn = superuser()) {
            UUID userId = TestFixtures.insertUser(conn, clinicA, "user-" + UUID.randomUUID(), "hash", "active", "Test");
            UUID membershipId = TestFixtures.insertMembership(conn, clinicA, userId, roleCode);
            DSL.using(conn, SQLDialect.POSTGRES)
                    .update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, employeeId)
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .execute();
        }
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}