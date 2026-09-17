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

        /** Base pay plus incentive; not stored, always derived from the two. */
        public BigDecimal totalPay() {
            return basePay.add(incentiveAmount);
        }
    }

    record ComponentScore(
            Category category,
            BigDecimal rawScore,
            BigDecimal weight,
            BigDecimal overrideFloor) {

        /** A category with no raw score is excluded from the final average (BR-G14/BR-G15). */
        public boolean included() {
            return rawScore != null;
        }
    }

    record Gamification(List<GoalProgress> goals, int streak, List<String> earnedBadges) {
    }

    record GoalProgress(String title, int target, int current) {
    }

    /** Raised when a frozen snapshot is edited without a preceding unlock. */
    class EvaluationConflictException extends RuntimeException {
        public EvaluationConflictException(String message) {
            super(message);
        }
    }
}