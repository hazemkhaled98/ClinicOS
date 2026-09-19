package com.clinicos.evaluation.api;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;

/**
 * UC-004/UC-005: runs the monthly scoring engine for one (employee, month),
 * persists closed months as write-once snapshots, and lets a manager nudge a
 * component with a floor override then reopen the snapshot for one edit.
 *
 * <p>Schema: V4 (snapshot/override/component), frozen-write guard: V10.
 */
public interface EvaluationService {

    /**
     * Month's evaluation. A closed (past) month is computed once and frozen as
     * a snapshot; re-evaluating it rehydrates the stored snapshot. An open
     * (current) month stays live. An already-unlocked snapshot is spent by the
     * next call — the snapshot is updated, re-frozen, and its components replaced.
     */
    MonthlyEvaluation evaluate(UUID clinicId, UUID employeeId, YearMonth month);

    /** Derived, read-only gamification snapshot for the employee's own screen (UC-005). */
    Gamification gamification(UUID clinicId, UUID employeeId, YearMonth month);

    /** Upserts a floor override for one component; does not touch the snapshot. */
    void setOverride(UUID clinicId, UUID employeeId, YearMonth month, Category category,
            BigDecimal floorValue, UUID setByMembershipId);

    /**
     * Reopens a frozen month for a single edit (instance independent of any
     * other change, as V10 requires). The next {@link #evaluate} re-freezes it.
     */
    void unlock(UUID clinicId, UUID employeeId, YearMonth month, UUID unlockedByMembershipId);

    /** Month: was evaluated/written, is frozen, non-null when finalScore present. */
    record MonthlyEvaluation(
            BigDecimal finalScore,
            BigDecimal coverage,
            List<ComponentScore> components,
            String tierName,
            BigDecimal incentiveAmount,
            BigDecimal basePay,
            int daysLogged,
            boolean frozen) {

        public BigDecimal totalPay() {
            return basePay.add(incentiveAmount);
        }
    }

    record ComponentScore(
            Category category,
            BigDecimal rawScore,
            BigDecimal weight,
            BigDecimal overrideFloor) {

        public boolean included() {
            return rawScore != null;
        }
    }

    record Gamification(List<GoalProgress> goals, int streak, List<String> earnedBadges) {
    }

    record GoalProgress(String title, int target, int current) {
    }

    /** The clinic's recorded operating volume for a month, or null when none. */
    BigDecimal volume(UUID clinicId, YearMonth month);

    /** Upserts the clinic's operating volume for a month (UC-009 step 4). */
    void recordVolume(UUID clinicId, YearMonth month, BigDecimal amount, UUID recordedByMembershipId);

    /** Today's pace of actual volume against the month-so-far target (UC-009 step 2, BR-001). */
    VolumePace volumePace(UUID clinicId, YearMonth month);

    /**
     * A clinic-wide live team summary for a month (UC-009 step 3): one row per
     * staffed employee (owner excluded, BR-G17), each scored through the same
     * engine as UC-004/UC-005. When no operating target is configured the
     * volume component is skipped (A1 / BR-G15), mirroring the per-employee
     * evaluation.
     */
    TeamScore teamScore(UUID clinicId, UUID employeeId, YearMonth month);

    /** All staffed employees' scores for a month (owner excluded), lowest first. */
    List<TeamScore> teamScores(UUID clinicId, YearMonth month);

    record TeamScore(
            UUID employeeId,
            String employeeName,
            String roleCode,
            BigDecimal finalScore,
            String tierName,
            BigDecimal incentiveAmount,
            BigDecimal totalPay,
            int daysLogged) {
    }

    /**
     * Derivative from the clinic-wide volume and the work calendar: how much of
     * the month has elapsed and the pace-adjusted target for those working days.
     */
    record VolumePace(
            BigDecimal monthlyTarget,
            BigDecimal actual,
            BigDecimal paceTarget,
            int workingDaysElapsed,
            int workingDaysInMonth) {

        /** Attainment of the full monthly target, capped at 100, for the progress bar. */
        public int pct() {
            if (actual == null || monthlyTarget == null
                    || monthlyTarget.signum() <= 0) {
                return 0;
            }
            return Math.min(100, actual.multiply(BigDecimal.valueOf(100))
                    .divideToIntegralValue(monthlyTarget).intValue());
        }
    }

    /** Raised when a frozen snapshot is edited without a preceding unlock. */
    class EvaluationConflictException extends RuntimeException {
        public EvaluationConflictException(String message) {
            super(message);
        }
    }
}
