package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.Routines.updateClinicIdentity;
import static com.clinicos.shared.jooq.tables.Clinic.CLINIC;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.ClinicProfileService;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.MembershipStatus;

@Service
public class DefaultClinicProfileService implements ClinicProfileService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$");
    private static final int SLUG_MIN = 3;
    private static final int SLUG_MAX = 40;

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final ActivityLogService activityLogService;
    private final NotificationService notificationService;

    public DefaultClinicProfileService(DSLContext dsl, TransactionTemplate transactionTemplate,
            ActivityLogService activityLogService, NotificationService notificationService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.activityLogService = activityLogService;
        this.notificationService = notificationService;
    }

    @Override
    public ClinicIdentity current(UUID clinicId) {
        requireBoundTenant(clinicId);
        return transactionTemplate.execute(status -> read(clinicId));
    }

    @Override
    public Map<String, String> update(UUID clinicId, UUID actorMembershipId, String name, String slug) {
        requireBoundTenant(clinicId);

        String trimmedName = name == null ? "" : name.trim();
        String trimmedSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);

        try {
            return transactionTemplate.execute(status -> {
                requireOwner(clinicId, actorMembershipId);
                ClinicIdentity current = read(clinicId);
                Map<String, String> fieldErrors = validate(trimmedName, trimmedSlug);
                if (current.slug().equals(trimmedSlug)) {
                    fieldErrors.remove("slug");
                }
                if (!fieldErrors.isEmpty()) {
                    return fieldErrors;
                }
                String[] previous = updateClinicIdentity(dsl.configuration(), clinicId, actorMembershipId,
                        trimmedName, trimmedSlug);
                ClinicIdentity before = new ClinicIdentity(previous[0], previous[1]);
                if (before.name().equals(trimmedName) && before.slug().equals(trimmedSlug)) {
                    return Map.<String, String>of();
                }
                activityLogService.logRequired(clinicId, actorMembershipId, "clinic.identity_changed", "clinic", clinicId,
                        Map.of(
                                "oldName", before.name(),
                                "newName", trimmedName,
                                "oldSlug", before.slug(),
                                "newSlug", trimmedSlug));
                notificationService.notifyAllMembers(clinicId, actorMembershipId,
                        NotificationKind.CLINIC_IDENTITY_CHANGED, Map.of("name", trimmedName, "slug", trimmedSlug));
                return Map.<String, String>of();
            });
        } catch (DuplicateKeyException e) {
            if (isSlugConflict(e)) {
                return Map.of("slug", "كود العيادة مستخدم بالفعل، اختر كودًا آخر");
            }
            throw e;
        }
    }

    private static Map<String, String> validate(String name, String slug) {
        Map<String, String> fieldErrors = new HashMap<>();
        if (name.isEmpty()) {
            fieldErrors.put("name", "اسم العيادة مطلوب");
        }
        if (slug.length() < SLUG_MIN || slug.length() > SLUG_MAX) {
            fieldErrors.put("slug", "كود العيادة يجب أن يكون بين " + SLUG_MIN + " و" + SLUG_MAX + " حرفًا");
        } else if (!SLUG.matcher(slug).matches()) {
            fieldErrors.put("slug", "كود العيادة يقبل أحرفًا إنجليزية صغيرة وأرقامًا وشرطات فقط، ولا يبدأ أو ينتهي بشرطة");
        }
        return fieldErrors;
    }

    private ClinicIdentity read(UUID clinicId) {
        return dsl.select(CLINIC.NAME, CLINIC.SLUG)
                .from(CLINIC)
                .where(CLINIC.ID.eq(clinicId))
                .fetchOne(r -> new ClinicIdentity(r.get(CLINIC.NAME), r.get(CLINIC.SLUG)));
    }

    private void requireBoundTenant(UUID clinicId) {
        if (clinicId == null || !TenantContext.get().filter(clinicId::equals).isPresent()) {
            throw new IllegalArgumentException("لا يمكن تعديل بيانات عيادة أخرى");
        }
    }

    private void requireOwner(UUID clinicId, UUID actorMembershipId) {
        boolean owner = dsl.fetchExists(dsl.selectOne()
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.ID.eq(actorMembershipId))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .and(ROLE.CODE.eq("owner")));
        if (!owner) {
            throw new IllegalArgumentException("تعديل بيانات العيادة متاح لمالك العيادة فقط");
        }
    }

    private static boolean isSlugConflict(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("clinic_slug_key");
    }
}
