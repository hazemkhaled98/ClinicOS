package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Notification.NOTIFICATION;
import static com.clinicos.shared.jooq.tables.Role.ROLE;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DefaultNotificationService implements NotificationService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultNotificationService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int notifyMembership(UUID clinicId, UUID membershipId, NotificationKind kind, Map<String, String> payload) {
        if (membershipId == null || kind == null) {
            throw new IllegalArgumentException("مستلم الإشعار أو نوعه مطلوب");
        }
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            if (!isActiveMember(clinicId, membershipId)) {
                return 0;
            }
            insert(clinicId, membershipId, kind, payload);
            return 1;
        });
    }

    @Override
    public int notifyEmployee(UUID clinicId, UUID employeeId, NotificationKind kind, Map<String, String> payload) {
        if (employeeId == null || kind == null) {
            throw new IllegalArgumentException("موظف الإشعار أو نوعه مطلوب");
        }
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            var recipients = dsl.select(MEMBERSHIP.ID)
                    .from(MEMBERSHIP)
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                    .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                    .fetchSet(MEMBERSHIP.ID);
            recipients.forEach(id -> insert(clinicId, id, kind, payload));
            return recipients.size();
        });
    }

    @Override
    public int notifyRoles(UUID clinicId, Set<String> roleCodes, NotificationKind kind, Map<String, String> payload) {
        if (roleCodes == null || roleCodes.isEmpty() || kind == null) {
            throw new IllegalArgumentException("دور واحد على الأقل أو نوع الإشعار مطلوب");
        }
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            var recipients = dsl.select(MEMBERSHIP.ID)
                    .from(MEMBERSHIP)
                    .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                    .and(ROLE.CODE.in(roleCodes))
                    .fetchSet(MEMBERSHIP.ID);
            recipients.forEach(id -> insert(clinicId, id, kind, payload));
            return recipients.size();
        });
    }

    @Override
    public int unreadCount(UUID clinicId, UUID membershipId) {
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            return dsl.fetchCount(NOTIFICATION,
                    NOTIFICATION.CLINIC_ID.eq(clinicId),
                    NOTIFICATION.RECIPIENT_MEMBERSHIP_ID.eq(membershipId),
                    NOTIFICATION.READ_AT.isNull());
        });
    }

    @Override
    public List<Notification> recent(UUID clinicId, UUID membershipId, int limit) {
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            return dsl.selectFrom(NOTIFICATION)
                    .where(NOTIFICATION.CLINIC_ID.eq(clinicId))
                    .and(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID.eq(membershipId))
                    .orderBy(NOTIFICATION.CREATED_AT.desc(), NOTIFICATION.ID.desc())
                    .limit(bounded)
                    .fetch(this::toNotification);
        });
    }

    @Override
    public Notification markRead(UUID clinicId, UUID membershipId, UUID notificationId) {
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            int updated = dsl.update(NOTIFICATION)
                    .set(NOTIFICATION.READ_AT, OffsetDateTime.now())
                    .where(NOTIFICATION.ID.eq(notificationId))
                    .and(NOTIFICATION.CLINIC_ID.eq(clinicId))
                    .and(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID.eq(membershipId))
                    .and(NOTIFICATION.READ_AT.isNull())
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("الإشعار غير موجود");
            }
            return dsl.selectFrom(NOTIFICATION)
                    .where(NOTIFICATION.ID.eq(notificationId))
                    .and(NOTIFICATION.CLINIC_ID.eq(clinicId))
                    .and(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID.eq(membershipId))
                    .fetchOne(this::toNotification);
        });
    }

    @Override
    public int markAllRead(UUID clinicId, UUID membershipId) {
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            return dsl.update(NOTIFICATION)
                    .set(NOTIFICATION.READ_AT, OffsetDateTime.now())
                    .where(NOTIFICATION.CLINIC_ID.eq(clinicId))
                    .and(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID.eq(membershipId))
                    .and(NOTIFICATION.READ_AT.isNull())
                    .execute();
        });
    }

    private void insert(UUID clinicId, UUID membershipId, NotificationKind kind, Map<String, String> payload) {
        dsl.insertInto(NOTIFICATION)
                .set(NOTIFICATION.CLINIC_ID, clinicId)
                .set(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID, membershipId)
                .set(NOTIFICATION.KIND, kind.literal())
                .set(NOTIFICATION.PAYLOAD, JSONB.valueOf(serialize(payload)))
                .execute();
    }

    private boolean isActiveMember(UUID clinicId, UUID membershipId) {
        return dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP)
                .where(MEMBERSHIP.ID.eq(membershipId))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active)));
    }

    private void guardTenant(UUID clinicId) {
        if (!TenantContext.get().filter(clinicId::equals).isPresent()) {
            throw new IllegalStateException("No tenant bound matching clinic " + clinicId);
        }
    }

    private Notification toNotification(org.jooq.Record r) {
        return new Notification(
                r.get(NOTIFICATION.ID),
                NotificationKind.fromLiteral(r.get(NOTIFICATION.KIND)),
                parsePayload(r.get(NOTIFICATION.PAYLOAD)),
                r.get(NOTIFICATION.CREATED_AT),
                r.get(NOTIFICATION.READ_AT));
    }

    private String serialize(Map<String, String> payload) {
        try {
            return JSON.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("تعذر تجهيز بيانات الإشعار", e);
        }
    }

    private Map<String, String> parsePayload(JSONB payload) {
        if (payload == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(payload.data(), PAYLOAD_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
