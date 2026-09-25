package com.clinicos.clinicconfig.api;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clinic-wide configuration read from one aggregate. Every read and mutation
 * runs inside the clinic bound to the current thread's {@code TenantContext};
 * callers must guarantee a tenant is bound before invoking (TenantSessionFilter
 * does for HTTP requests, tests seed it explicitly).
 */
public interface ClinicSettingsService {

    /**
     * Full aggregate: shift/grace/volume/academy settings, the six evaluation
     * weights and the incentive tiers ordered best to worst.
     */
    ClinicSettings get(UUID clinicId);

    /**
     * Updates the clinic-duty card fields (shift start/end, late grace, working
     * days per month, academy pass score). Volume target is updated separately
     * via {@link #updateVolumeTarget}.
     */
    void updateDuty(UUID clinicId, LocalTime defaultShiftStart, LocalTime defaultShiftEnd,
            int lateGraceMinutes, int workingDaysPerMonth, int academyPassScore);

    void updateVolumeTarget(UUID clinicId, BigDecimal volumeTarget);

    void updateInvoicePhotoRequired(UUID clinicId, boolean required);

    /**
     * Replaces every weight row. Weights must be non-negative and sum to 100
     * (BR-G07 evaluation weighs assessed components only, renormalised by
     * assessed weight -- the sum must stay 100).
     */
    void updateWeights(UUID clinicId, List<CategoryWeight> weights);

    /**
     * Replaces every tier row (BR-G07: a month's incentive is
     * {@code maxIncentive x pct}, where the tier comes from the employee's
     * final score band).
     */
    void updateTiers(UUID clinicId, List<Tier> tiers);

    record ClinicSettings(
            LocalTime defaultShiftStart,
            LocalTime defaultShiftEnd,
            int lateGraceMinutes,
            int workingDaysPerMonth,
            BigDecimal volumeTarget,
            int academyPassScore,
            List<CategoryWeight> weights,
            List<Tier> tiers,
            boolean invoicePhotoRequired) {
    }

    record CategoryWeight(Category category, BigDecimal weight) {
    }

    record Tier(String name, BigDecimal minScore, BigDecimal incentivePct) {
    }

    /** The six evaluated components (order used by the weights card). */
    enum Category {
        COMPLETION("completion", "الإنجاز"),
        FANNI("fanni", "الفني"),
        SOLOOKI("solooki", "السلوكي"),
        IBDA3("ibda3", "الإبداع"),
        ATTENDANCE("attendance", "الانضباط"),
        VOLUME("volume", "حجم الإنتاج");

        private final String code;
        private final String arabicName;

        Category(String code, String arabicName) {
            this.code = code;
            this.arabicName = arabicName;
        }

        public String code() {
            return code;
        }

        public String arabicName() {
            return arabicName;
        }

        public static Category fromCode(String code) {
            for (Category category : values()) {
                if (category.code().equals(code)) {
                    return category;
                }
            }
            throw new IllegalArgumentException("كود مكوّن التقييم غير معروف: " + code);
        }
    }

    /**
     * Field-level validation failure (Arabic messages, keyed by field name).
     * Raised before any SQL runs.
     */
    class ClinicSettingsValidationException extends RuntimeException {
        private final Map<String, String> fieldErrors;

        public ClinicSettingsValidationException(Map<String, String> fieldErrors) {
            super(String.join("؛ ", fieldErrors.values()));
            this.fieldErrors = fieldErrors;
        }

        public Map<String, String> fieldErrors() {
            return fieldErrors;
        }
    }
}
