package com.clinicos.evaluation.internal;

import static com.clinicos.shared.jooq.tables.EvaluationComponent.EVALUATION_COMPONENT;
import static com.clinicos.shared.jooq.tables.EvaluationSnapshot.EVALUATION_SNAPSHOT;
import static com.clinicos.shared.jooq.tables.OperationsVolume.OPERATIONS_VOLUME;
import static com.clinicos.shared.jooq.tables.PerformanceOverride.PERFORMANCE_OVERRIDE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.ClinicSettingsService.CategoryWeight;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettings;
import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.BadgeThreshold;
import com.clinicos.clinicconfig.api.GamificationService.WeeklyGoal;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.evaluation.api.EvaluationService;
import com.clinicos.evaluation.api.EvaluationService.ComponentScore;
import com.clinicos.evaluation.api.EvaluationService.Gamification;
import com.clinicos.evaluation.api.EvaluationService.GoalProgress;
import com.clinicos.evaluation.api.EvaluationService.MonthlyEvaluation;
import com.clinicos.evaluation.api.EvaluationService.TeamScore;
import com.clinicos.evaluation.api.EvaluationService.VolumePace;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.shared.jooq.enums.EvalCategory;
import com.clinicos.shared.jooq.tables.records.EvaluationSnapshotRecord;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EvaluationInputService;
import com.clinicos.staff.api.EvaluationInputService.MonthData;

@Service
public class DefaultEvaluationService implements EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEvaluationService.class);

    private final EvaluationInputService evaluationInput;
    private final EmployeeService employeeService;
    private final ClinicSettingsService clinicSettings;
    private final WorkCalendarService workCalendar;
    private final GamificationService gamificationService;
    private final UserAdminService userAdminService;
    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultEvaluationService(EvaluationInputService evaluationInput, EmployeeService employeeService,
            ClinicSettingsService clinicSettings, WorkCalendarService workCalendar,
            GamificationService gamificationService, UserAdminService userAdminService, DSLContext dsl,
            TransactionTemplate transactionTemplate) {
        this.evaluationInput = evaluationInput;
        this.employeeService = employeeService;
        this.clinicSettings = clinicSettings;
        this.workCalendar = workCalendar;
        this.gamificationService = gamificationService;
        this.userAdminService = userAdminService;
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public MonthlyEvaluation evaluate(UUID clinicId, UUID employeeId, YearMonth month) {
        return transactionTemplate.execute(status -> doEvaluate(clinicId, employeeId, month));
    }

    private MonthlyEvaluation doEvaluate(UUID clinicId, UUID employeeId, YearMonth month) {
        MonthData md = evaluationInput.forMonth(clinicId, employeeId, month);
        EmployeeService.Employee employee = employeeService.findById(clinicId, employeeId);
        if (employee == null) {
            log.warn("evaluation requested for unknown employee: clinicId={}, employeeId={}, month={}",
                    clinicId, employeeId, month);
            return null;
        }
        ClinicSettings settings = clinicSettings.get(clinicId);
        LocalDate firstOfMonth = month.atDay(1);
        LocalDate asOf = month.equals(YearMonth.now()) ? LocalDate.now() : month.atEndOfMonth();

        Map<Category, BigDecimal> weights = settings.weights().stream()
                .collect(Collectors.toMap(CategoryWeight::category, CategoryWeight::weight));
        boolean customShift = employee.customShift();
        ScoringEngine.EngineConfig config = new ScoringEngine.EngineConfig(weights,
                settings.volumeTarget(),
                customShift ? employee.shiftStart() : settings.defaultShiftStart(),
                customShift ? employee.shiftEnd() : settings.defaultShiftEnd(),
                settings.lateGraceMinutes(),
                workdays(clinicId, month, employeeId),
                employee.maxIncentive() != null ? employee.maxIncentive() : BigDecimal.ZERO,
                settings.tiers());

        Map<Category, BigDecimal> overrides = loadOverrides(clinicId, employeeId, firstOfMonth);
        ScoringEngine.EngineInput input = new ScoringEngine.EngineInput(md.tasks(), md.completions(),
                md.loggedDates(), md.attendance(), md.assignments(), asOf, md.volumeActual(), md.lastRunByTask());
        ScoringEngine.EngineResult result = ScoringEngine.evaluate(input, config, overrides);

        if (month.isBefore(YearMonth.now())) {
            return persistClosedMonth(clinicId, employeeId, firstOfMonth, employee, md, result, overrides);
        }
        return toEvaluation(employee, md, result, overrides, false);
    }

    private MonthlyEvaluation persistClosedMonth(UUID clinicId, UUID employeeId, LocalDate firstOfMonth,
            EmployeeService.Employee employee, MonthData md, ScoringEngine.EngineResult result,
            Map<Category, BigDecimal> overrides) {
        EvaluationSnapshotRecord snap = dsl.selectFrom(EVALUATION_SNAPSHOT)
                .where(EVALUATION_SNAPSHOT.CLINIC_ID.eq(clinicId))
                .and(EVALUATION_SNAPSHOT.EMPLOYEE_ID.eq(employeeId))
                .and(EVALUATION_SNAPSHOT.PERIOD_MONTH.eq(firstOfMonth))
                .forUpdate()
                .fetchOne();
        if (snap == null) {
            UUID snapshotId = UUID.randomUUID();
            try {
                dsl.insertInto(EVALUATION_SNAPSHOT)
                        .set(EVALUATION_SNAPSHOT.ID, snapshotId)
                        .set(EVALUATION_SNAPSHOT.CLINIC_ID, clinicId)
                        .set(EVALUATION_SNAPSHOT.EMPLOYEE_ID, employeeId)
                        .set(EVALUATION_SNAPSHOT.PERIOD_MONTH, firstOfMonth)
                        .set(EVALUATION_SNAPSHOT.FINAL_SCORE, result.finalScore())
                        .set(EVALUATION_SNAPSHOT.INCENTIVE_AMOUNT, result.incentiveAmount())
                        .execute();
                insertComponents(snapshotId, result);
            } catch (DataIntegrityViolationException e) {
                log.warn("evaluation snapshot write rejected: clinicId={}, employeeId={}, month={}",
                        clinicId, employeeId, firstOfMonth, e);
                throw new EvaluationConflictException(
                        "الشهر مقفل للتقييم بالفعل؛ افتحه أولاً من شاشة التقييم ثم أعد الحساب");
            }
            return toEvaluation(employee, md, result, overrides, true);
        }
        if (snap.getUnlockedAt() == null) {
            return rehydrate(snap, clinicId, employee, md, overrides);
        }
        dsl.deleteFrom(EVALUATION_COMPONENT).where(EVALUATION_COMPONENT.SNAPSHOT_ID.eq(snap.getId())).execute();
        dsl.update(EVALUATION_SNAPSHOT)
                .set(EVALUATION_SNAPSHOT.FINAL_SCORE, result.finalScore())
                .set(EVALUATION_SNAPSHOT.INCENTIVE_AMOUNT, result.incentiveAmount())
                .set(EVALUATION_SNAPSHOT.FROZEN_AT, OffsetDateTime.now())
                .set(EVALUATION_SNAPSHOT.UNLOCKED_AT, (OffsetDateTime) null)
                .set(EVALUATION_SNAPSHOT.UNLOCKED_BY, (UUID) null)
                .where(EVALUATION_SNAPSHOT.ID.eq(snap.getId()))
                .execute();
        insertComponents(snap.getId(), result);
        return toEvaluation(employee, md, result, overrides, true);
    }

    private MonthlyEvaluation rehydrate(EvaluationSnapshotRecord snap, UUID clinicId,
            EmployeeService.Employee employee, MonthData md, Map<Category, BigDecimal> overrides) {
        List<ComponentScore> components = dsl.selectFrom(EVALUATION_COMPONENT)
                .where(EVALUATION_COMPONENT.SNAPSHOT_ID.eq(snap.getId()))
                .fetch(r -> new ComponentScore(
                        Category.fromCode(r.getCategory().getLiteral()),
                        r.getRawScore(),
                        r.getWeight(),
                        overrides.get(Category.fromCode(r.getCategory().getLiteral()))));

        BigDecimal assessedWeight = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (ComponentScore c : components) {
            totalWeight = totalWeight.add(c.weight());
            if (c.included()) {
                assessedWeight = assessedWeight.add(c.weight());
            }
        }
        BigDecimal coverage = totalWeight.signum() > 0
                ? assessedWeight.divide(totalWeight, 4, java.math.RoundingMode.HALF_UP) : null;
        String tierName = snap.getFinalScore() == null ? null : tierName(clinicId, snap.getFinalScore());
        BigDecimal basePay = employee.basePay() != null ? employee.basePay() : BigDecimal.ZERO;
        BigDecimal incentive = snap.getIncentiveAmount() == null ? BigDecimal.ZERO : snap.getIncentiveAmount();
        return new MonthlyEvaluation(snap.getFinalScore(), coverage, components, tierName,
                incentive, basePay, md.loggedDates().size(), true);
    }

    private String tierName(UUID clinicId, BigDecimal finalScore) {
        return clinicSettings.get(clinicId).tiers().stream()
                .filter(t -> finalScore.compareTo(t.minScore()) >= 0)
                .max((a, b) -> a.minScore().compareTo(b.minScore()))
                .map(ClinicSettingsService.Tier::name)
                .orElse(null);
    }

    private void insertComponents(UUID snapshotId, ScoringEngine.EngineResult result) {
        for (ScoringEngine.ComponentScore c : result.components().values()) {
            dsl.insertInto(EVALUATION_COMPONENT)
                    .set(EVALUATION_COMPONENT.SNAPSHOT_ID, snapshotId)
                    .set(EVALUATION_COMPONENT.CATEGORY, EvalCategory.valueOf(c.category().code()))
                    .set(EVALUATION_COMPONENT.RAW_SCORE, c.rawScore())
                    .set(EVALUATION_COMPONENT.WEIGHT, c.weight())
                    .set(EVALUATION_COMPONENT.INCLUDED, c.included())
                    .execute();
        }
    }

    private MonthlyEvaluation toEvaluation(EmployeeService.Employee employee, MonthData md,
            ScoringEngine.EngineResult result, Map<Category, BigDecimal> overrides, boolean frozen) {
        List<ComponentScore> components = result.components().entrySet().stream()
                .map(e -> new ComponentScore(e.getKey(), e.getValue().rawScore(), e.getValue().weight(),
                        overrides.get(e.getKey())))
                .toList();
        BigDecimal basePay = employee.basePay() != null ? employee.basePay() : BigDecimal.ZERO;
        BigDecimal incentive = result.incentiveAmount() == null ? BigDecimal.ZERO : result.incentiveAmount();
        return new MonthlyEvaluation(result.finalScore(), result.coverage(), components,
                result.tier() == null ? null : result.tier().name(), incentive, basePay,
                md.loggedDates().size(), frozen);
    }

    private Map<Category, BigDecimal> loadOverrides(UUID clinicId, UUID employeeId, LocalDate firstOfMonth) {
        Map<Category, BigDecimal> overrides = new HashMap<>();
        dsl.selectFrom(PERFORMANCE_OVERRIDE)
                .where(PERFORMANCE_OVERRIDE.EMPLOYEE_ID.eq(employeeId))
                .and(PERFORMANCE_OVERRIDE.PERIOD_MONTH.eq(firstOfMonth))
                .fetch(r -> overrides.put(Category.fromCode(r.getCategory().getLiteral()), r.getFloorValue()));
        return overrides;
    }

    private List<LocalDate> workdays(UUID clinicId, YearMonth month, UUID employeeId) {
        List<LocalDate> workdays = new ArrayList<>();
        LocalDate day = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        while (!day.isAfter(end)) {
            if (workCalendar.isWorkday(clinicId, day, employeeId)) {
                workdays.add(day);
            }
            day = day.plusDays(1);
        }
        return workdays;
    }

    @Override
    public void setOverride(UUID clinicId, UUID employeeId, YearMonth month, Category category,
            BigDecimal floorValue, UUID setByMembershipId) {
        if (floorValue == null || floorValue.compareTo(BigDecimal.ZERO) < 0
                || floorValue.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("قيمة الحد الأدنى يجب أن تكون بين 0 و 100");
        }
        transactionTemplate.executeWithoutResult(status -> dsl.insertInto(PERFORMANCE_OVERRIDE)
                .set(PERFORMANCE_OVERRIDE.EMPLOYEE_ID, employeeId)
                .set(PERFORMANCE_OVERRIDE.PERIOD_MONTH, month.atDay(1))
                .set(PERFORMANCE_OVERRIDE.CATEGORY, EvalCategory.valueOf(category.code()))
                .set(PERFORMANCE_OVERRIDE.FLOOR_VALUE, floorValue)
                .set(PERFORMANCE_OVERRIDE.SET_BY, setByMembershipId)
                .onConflict(PERFORMANCE_OVERRIDE.EMPLOYEE_ID, PERFORMANCE_OVERRIDE.PERIOD_MONTH,
                        PERFORMANCE_OVERRIDE.CATEGORY)
                .doUpdate()
                .set(PERFORMANCE_OVERRIDE.FLOOR_VALUE, floorValue)
                .set(PERFORMANCE_OVERRIDE.SET_BY, setByMembershipId)
                .set(PERFORMANCE_OVERRIDE.SET_AT, OffsetDateTime.now())
                .execute());
    }

    @Override
    public Gamification gamification(UUID clinicId, UUID employeeId, YearMonth month) {
        MonthData md = evaluationInput.forMonth(clinicId, employeeId, month);
        LocalDate asOf = month.equals(YearMonth.now()) ? LocalDate.now() : month.atEndOfMonth();
        LocalDate weekStart = asOf.minusDays((asOf.getDayOfWeek().getValue() % 7));
        LocalDate weekEnd = weekStart.plusDays(6);
        int completionsThisWeek = (int) md.completions().stream()
                .map(c -> c.workDate())
                .filter(d -> !d.isBefore(weekStart) && !d.isAfter(weekEnd))
                .count();

        List<GoalProgress> goals = gamificationService.getGoals(clinicId).stream()
                .filter(g -> g.title() != null && !g.title().isBlank())
                .map(g -> new GoalProgress(g.title(), g.target(), completionsThisWeek))
                .toList();

        int streak = 0;
        LocalDate day = asOf;
        LocalDate monthStart = month.atDay(1);
        Set<LocalDate> checkedInDates = md.attendance().stream()
                .filter(a -> a.checkInTime() != null)
                .map(a -> a.workDate())
                .collect(Collectors.toSet());
        while (!day.isBefore(monthStart)) {
            if (workCalendar.isWorkday(clinicId, day, employeeId)) {
                if (!checkedInDates.contains(day)) {
                    break;
                }
                streak++;
            }
            day = day.minusDays(1);
        }

        int totalCompletions = md.completions().size();
        List<String> earnedBadges = gamificationService.getThresholds(clinicId).stream()
                .filter(t -> t.threshold() > 0 && totalCompletions >= t.threshold())
                .map(BadgeThreshold::name)
                .toList();

        return new Gamification(goals, streak, earnedBadges);
    }

    @Override
    public BigDecimal volume(UUID clinicId, YearMonth month) {
        return transactionTemplate.execute(status -> dsl.select(OPERATIONS_VOLUME.AMOUNT)
                .from(OPERATIONS_VOLUME)
                .where(OPERATIONS_VOLUME.CLINIC_ID.eq(clinicId))
                .and(OPERATIONS_VOLUME.PERIOD_MONTH.eq(month.atDay(1)))
                .fetchOneInto(BigDecimal.class));
    }

    @Override
    public void recordVolume(UUID clinicId, YearMonth month, BigDecimal amount, UUID recordedByMembershipId) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("حجم الإنتاج يجب أن يكون رقماً غير سالب");
        }
        transactionTemplate.executeWithoutResult(status -> dsl.insertInto(OPERATIONS_VOLUME)
                .set(OPERATIONS_VOLUME.CLINIC_ID, clinicId)
                .set(OPERATIONS_VOLUME.PERIOD_MONTH, month.atDay(1))
                .set(OPERATIONS_VOLUME.AMOUNT, amount)
                .set(OPERATIONS_VOLUME.RECORDED_BY, recordedByMembershipId)
                .onConflict(OPERATIONS_VOLUME.CLINIC_ID, OPERATIONS_VOLUME.PERIOD_MONTH)
                .doUpdate()
                .set(OPERATIONS_VOLUME.AMOUNT, amount)
                .set(OPERATIONS_VOLUME.RECORDED_BY, recordedByMembershipId)
                .set(OPERATIONS_VOLUME.RECORDED_AT, OffsetDateTime.now())
                .execute());
    }

    @Override
    public VolumePace volumePace(UUID clinicId, YearMonth month) {
        BigDecimal monthlyTarget = clinicSettings.get(clinicId).volumeTarget();
        LocalDate end = month.equals(YearMonth.now()) ? LocalDate.now() : month.atEndOfMonth();
        int elapsed = workCalendar.workdaysBetween(clinicId, month.atDay(1), end, null);
        int total = workCalendar.workdaysBetween(clinicId, month.atDay(1), month.atEndOfMonth(), null);
        BigDecimal paceTarget = monthlyTarget == null || monthlyTarget.signum() <= 0 || total == 0 ? null
                : monthlyTarget.multiply(BigDecimal.valueOf(elapsed))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new VolumePace(monthlyTarget, volume(clinicId, month), paceTarget, elapsed, total);
    }

    @Override
    public TeamScore teamScore(UUID clinicId, UUID employeeId, YearMonth month) {
        EmployeeService.Employee employee = employeeService.findById(clinicId, employeeId);
        if (employee == null) {
            return null;
        }
        String roleCode = roleCodeOf(clinicId, employeeId);
        return transactionTemplate.execute(status -> doTeamScore(clinicId, employee, roleCode, month));
    }

    @Override
    public List<TeamScore> teamScores(UUID clinicId, YearMonth month) {
        List<EmployeeService.Employee> employees = employeeService.list(clinicId);
        Map<UUID, String> roles = userAdminService.list(clinicId).stream()
                .filter(u -> u.employeeId() != null)
                .collect(Collectors.toMap(u -> u.employeeId(), u -> u.roleCode(), (a, b) -> a));
        return transactionTemplate.execute(status -> employees.stream()
                .filter(e -> !"owner".equals(roles.get(e.id())))
                .map(e -> doTeamScore(clinicId, e, roles.get(e.id()), month))
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing(TeamScore::finalScore,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList());
    }

    private String roleCodeOf(UUID clinicId, UUID employeeId) {
        return userAdminService.list(clinicId).stream()
                .filter(u -> employeeId.equals(u.employeeId()))
                .map(UserAdminService.UserSummary::roleCode)
                .findFirst()
                .orElse(null);
    }

    private TeamScore doTeamScore(UUID clinicId, EmployeeService.Employee employee, String roleCode, YearMonth month) {
        if ("owner".equals(roleCode)) {
            return null;
        }
        MonthlyEvaluation evaluation = doEvaluate(clinicId, employee.id(), month);
        if (evaluation == null) {
            return null;
        }
        return new TeamScore(employee.id(), employee.name(), roleCode, evaluation.finalScore(),
                evaluation.tierName(), evaluation.incentiveAmount(), evaluation.totalPay(), evaluation.daysLogged());
    }

    @Override
    public void unlock(UUID clinicId, UUID employeeId, YearMonth month, UUID unlockedByMembershipId) {
        transactionTemplate.executeWithoutResult(status -> dsl.update(EVALUATION_SNAPSHOT)
                .set(EVALUATION_SNAPSHOT.UNLOCKED_AT, OffsetDateTime.now())
                .set(EVALUATION_SNAPSHOT.UNLOCKED_BY, unlockedByMembershipId)
                .where(EVALUATION_SNAPSHOT.CLINIC_ID.eq(clinicId))
                .and(EVALUATION_SNAPSHOT.EMPLOYEE_ID.eq(employeeId))
                .and(EVALUATION_SNAPSHOT.PERIOD_MONTH.eq(month.atDay(1)))
                .and(EVALUATION_SNAPSHOT.UNLOCKED_AT.isNull())
                .execute());
    }
}
