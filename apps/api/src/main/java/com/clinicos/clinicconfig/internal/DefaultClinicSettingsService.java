package com.clinicos.clinicconfig.internal;

import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static com.clinicos.shared.jooq.tables.EvaluationWeight.EVALUATION_WEIGHT;
import static com.clinicos.shared.jooq.tables.IncentiveTier.INCENTIVE_TIER;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.ClinicSettingsService.CategoryWeight;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettings;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettingsValidationException;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Tier;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;

@Service
public class DefaultClinicSettingsService implements ClinicSettingsService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final NotificationService notificationService;

    public DefaultClinicSettingsService(DSLContext dsl, TransactionTemplate transactionTemplate,
            NotificationService notificationService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.notificationService = notificationService;
    }

    @Override
    public ClinicSettings get(UUID clinicId) {
        return transactionTemplate.execute(status -> {
            var settings = dsl.selectFrom(CLINIC_SETTINGS)
                    .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                    .fetchOne();
            if (settings == null) {
                throw new IllegalArgumentException("إعدادات العيادة غير موجودة");
            }
            List<CategoryWeight> weights = dsl.selectFrom(EVALUATION_WEIGHT)
                    .where(EVALUATION_WEIGHT.CLINIC_ID.eq(clinicId))
                    .fetch(weight -> new CategoryWeight(toCategory(weight.getCategory()), weight.getWeight()));
            List<Tier> tiers = dsl.selectFrom(INCENTIVE_TIER)
                    .where(INCENTIVE_TIER.CLINIC_ID.eq(clinicId))
                    .orderBy(INCENTIVE_TIER.MIN_SCORE.desc())
                    .fetch(tier -> new Tier(tier.getName(), tier.getMinScore(), tier.getIncentivePct()));
            return new ClinicSettings(
                    settings.getDefaultShiftStart(),
                    settings.getDefaultShiftEnd(),
                    settings.getLateGraceMinutes(),
                    settings.getWorkingDaysPerMonth(),
                    settings.getVolumeTarget(),
                    settings.getAcademyPassScore(),
                    weights,
                    tiers,
                    settings.getInvoicePhotoRequired());
        });
    }

    @Override
    public void updateDuty(UUID clinicId, LocalTime defaultShiftStart, LocalTime defaultShiftEnd,
            int lateGraceMinutes, int workingDaysPerMonth, int academyPassScore, UUID actorMembershipId) {
        Map<String, String> fieldErrors = new HashMap<>();
        if (defaultShiftStart == null || defaultShiftEnd == null) {
            fieldErrors.put("shift", "وقت بداية الدوام ووقت نهايته مطلوبان");
        } else if (defaultShiftStart.isAfter(defaultShiftEnd)) {
            fieldErrors.put("shift", "وقت بداية الدوام يجب أن يسبق وقت النهاية");
        }
        if (lateGraceMinutes < 0) {
            fieldErrors.put("lateGraceMinutes", "مهلة التأخير لا يمكن أن تكون سالبة");
        }
        if (workingDaysPerMonth <= 0) {
            fieldErrors.put("workingDaysPerMonth", "أيام العمل الشهرية يجب أن تكون أكبر من صفر");
        }
        if (academyPassScore < 0 || academyPassScore > 100) {
            fieldErrors.put("academyPassScore", "درجة النجاح في الأكاديمية يجب أن تكون بين 0 و 100");
        }
        throwIfAny(fieldErrors);
        int updated = transactionTemplate.execute(status -> dsl.update(CLINIC_SETTINGS)
                .set(CLINIC_SETTINGS.DEFAULT_SHIFT_START, defaultShiftStart)
                .set(CLINIC_SETTINGS.DEFAULT_SHIFT_END, defaultShiftEnd)
                .set(CLINIC_SETTINGS.LATE_GRACE_MINUTES, lateGraceMinutes)
                .set(CLINIC_SETTINGS.WORKING_DAYS_PER_MONTH, workingDaysPerMonth)
                .set(CLINIC_SETTINGS.ACADEMY_PASS_SCORE, academyPassScore)
                .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                .execute());
        if (updated > 0) {
            notifyOwners(clinicId, actorMembershipId, "الدوام");
        }
    }

    @Override
    public void updateVolumeTarget(UUID clinicId, BigDecimal volumeTarget, UUID actorMembershipId) {
        Map<String, String> fieldErrors = new HashMap<>();
        if (volumeTarget == null || volumeTarget.signum() < 0) {
            fieldErrors.put("volumeTarget", "هدف الفواتير الشهري لا يمكن أن يكون سالباً");
        }
        throwIfAny(fieldErrors);
        int updated = transactionTemplate.execute(status -> dsl.update(CLINIC_SETTINGS)
                .set(CLINIC_SETTINGS.VOLUME_TARGET, volumeTarget)
                .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                .execute());
        if (updated > 0) {
            notifyOwners(clinicId, actorMembershipId, "هدف الفواتير");
        }
    }

    @Override
    public void updateInvoicePhotoRequired(UUID clinicId, boolean required, UUID actorMembershipId) {
        int updated = transactionTemplate.execute(status -> dsl.update(CLINIC_SETTINGS)
                .set(CLINIC_SETTINGS.INVOICE_PHOTO_REQUIRED, required)
                .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                .execute());
        if (updated > 0) {
            notifyOwners(clinicId, actorMembershipId, "سياسة صور الفواتير");
        }
    }

    @Override
    public void updateWeights(UUID clinicId, List<CategoryWeight> weights, UUID actorMembershipId) {
        Map<String, String> fieldErrors = new HashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        Map<Category, Boolean> seen = new HashMap<>();
        List<CategoryWeight> submitted = weights == null ? List.of() : weights;
        for (CategoryWeight weight : submitted) {
            if (weight.category() == null || weight.weight() == null) {
                fieldErrors.put("weights", "أوزان مكونات التقييم غير مكتملة");
                break;
            }
            if (weight.weight().signum() < 0 || weight.weight().compareTo(HUNDRED) > 0) {
                fieldErrors.put("weights", "كل وزن يجب أن يكون بين 0 و 100");
                break;
            }
            if (seen.put(weight.category(), Boolean.TRUE) != null) {
                fieldErrors.put("weights", "لا يمكن تكرار نفس مكون التقييم");
                break;
            }
            sum = sum.add(weight.weight());
        }
        if (seen.size() != Category.values().length) {
            fieldErrors.putIfAbsent("weights", "يجب تحديد وزن لكل مكونات التقييم الستة");
        }
        if (sum.compareTo(HUNDRED) != 0) {
            fieldErrors.putIfAbsent("weights", "مجموع أوزان مكونات التقييم يجب أن يساوي 100");
        }
        throwIfAny(fieldErrors);
        transactionTemplate.executeWithoutResult(status -> {
            dsl.deleteFrom(EVALUATION_WEIGHT)
                    .where(EVALUATION_WEIGHT.CLINIC_ID.eq(clinicId))
                    .execute();
            for (CategoryWeight weight : submitted) {
                dsl.insertInto(EVALUATION_WEIGHT)
                        .set(EVALUATION_WEIGHT.CLINIC_ID, clinicId)
                        .set(EVALUATION_WEIGHT.CATEGORY, toDbCategory(weight.category()))
                        .set(EVALUATION_WEIGHT.WEIGHT, weight.weight())
                        .execute();
            }
        });
        notifyOwners(clinicId, actorMembershipId, "أوزان التقييم");
    }

    @Override
    public void updateTiers(UUID clinicId, List<Tier> tiers, UUID actorMembershipId) {
        Map<String, String> fieldErrors = new HashMap<>();
        if (tiers == null || tiers.isEmpty()) {
            fieldErrors.put("tiers", "يجب وجود شريحة واحدة على الأقل");
        } else {
            Map<String, Boolean> names = new HashMap<>();
            for (int i = 0; i < tiers.size(); i++) {
                Tier tier = tiers.get(i);
                if (tier.name() == null || tier.name().isBlank()) {
                    fieldErrors.put("tiers", "اسم الشريحة مطلوب");
                    break;
                }
                if (names.put(tier.name().trim(), Boolean.TRUE) != null) {
                    fieldErrors.put("tiers", "أسماء الشرائح يجب أن تكون مختلفة");
                    break;
                }
                if (tier.minScore() == null || tier.minScore().signum() < 0
                        || tier.minScore().compareTo(HUNDRED) > 0) {
                    fieldErrors.put("tiers", "الحد الأدنى لكل شريحة يجب أن يكون بين 0 و 100");
                    break;
                }
                if (tier.incentivePct() == null || tier.incentivePct().signum() < 0
                        || tier.incentivePct().compareTo(HUNDRED) > 0) {
                    fieldErrors.put("tiers", "نسبة الحافز لكل شريحة يجب أن تكون بين 0 و 100");
                    break;
                }
            }
        }
        throwIfAny(fieldErrors);
        transactionTemplate.executeWithoutResult(status -> {
            dsl.deleteFrom(INCENTIVE_TIER)
                    .where(INCENTIVE_TIER.CLINIC_ID.eq(clinicId))
                    .execute();
            for (Tier tier : tiers) {
                dsl.insertInto(INCENTIVE_TIER)
                        .set(INCENTIVE_TIER.CLINIC_ID, clinicId)
                        .set(INCENTIVE_TIER.NAME, tier.name().trim())
                        .set(INCENTIVE_TIER.MIN_SCORE, tier.minScore())
                        .set(INCENTIVE_TIER.INCENTIVE_PCT, tier.incentivePct())
                        .execute();
            }
        });
        notifyOwners(clinicId, actorMembershipId, "شرائح الحافز");
    }

    private void notifyOwners(UUID clinicId, UUID actorMembershipId, String area) {
        notificationService.notifyRoles(clinicId, actorMembershipId, Set.of("owner"),
                NotificationKind.CLINIC_SETTINGS_CHANGED, Map.of("area", area));
    }

    private static void throwIfAny(Map<String, String> fieldErrors) {
        if (!fieldErrors.isEmpty()) {
            throw new ClinicSettingsValidationException(fieldErrors);
        }
    }

    private static Category toCategory(com.clinicos.shared.jooq.enums.EvalCategory category) {
        return switch (category) {
            case completion -> Category.COMPLETION;
            case fanni -> Category.FANNI;
            case solooki -> Category.SOLOOKI;
            case ibda3 -> Category.IBDA3;
            case attendance -> Category.ATTENDANCE;
            case volume -> Category.VOLUME;
        };
    }

    private static com.clinicos.shared.jooq.enums.EvalCategory toDbCategory(Category category) {
        return switch (category) {
            case COMPLETION -> com.clinicos.shared.jooq.enums.EvalCategory.completion;
            case FANNI -> com.clinicos.shared.jooq.enums.EvalCategory.fanni;
            case SOLOOKI -> com.clinicos.shared.jooq.enums.EvalCategory.solooki;
            case IBDA3 -> com.clinicos.shared.jooq.enums.EvalCategory.ibda3;
            case ATTENDANCE -> com.clinicos.shared.jooq.enums.EvalCategory.attendance;
            case VOLUME -> com.clinicos.shared.jooq.enums.EvalCategory.volume;
        };
    }
}
