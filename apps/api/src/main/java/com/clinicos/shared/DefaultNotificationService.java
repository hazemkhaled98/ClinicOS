package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.AppUser.APP_USER;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.MembershipPermission.MEMBERSHIP_PERMISSION;
import static com.clinicos.shared.jooq.tables.Notification.NOTIFICATION;
import static com.clinicos.shared.jooq.tables.Permission.PERMISSION;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.RolePermission.ROLE_PERMISSION;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
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
    public int notifyMembership(UUID clinicId, UUID actorMembershipId, UUID membershipId, NotificationKind kind,
            Map<String, String> payload) {
        if (actorMembershipId == null || membershipId == null || kind == null) {
            throw new IllegalArgumentException("مستلم الإشعار أو نوعه مطلوب");
        }
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            Map<String, String> enriched = enrichPayload(clinicId, actorMembershipId, kind, payload);
            validatePayloadForWrite(kind, enriched);
            if (!isActiveMember(clinicId, membershipId)) {
                return 0;
            }
            insert(clinicId, membershipId, kind, enriched);
            return 1;
        });
    }

    @Override
    public int notifyEmployee(UUID clinicId, UUID actorMembershipId, UUID employeeId, NotificationKind kind,
            Map<String, String> payload) {
        if (actorMembershipId == null || employeeId == null || kind == null) {
            throw new IllegalArgumentException("موظف الإشعار أو نوعه مطلوب");
        }
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            Map<String, String> enriched = enrichPayload(clinicId, actorMembershipId, kind, payload);
            validatePayloadForWrite(kind, enriched);
            var recipients = dsl.select(MEMBERSHIP.ID)
                    .from(MEMBERSHIP)
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                    .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                    .fetchSet(MEMBERSHIP.ID);
            recipients.forEach(id -> insert(clinicId, id, kind, enriched));
            return recipients.size();
        });
    }

    @Override
    public int notifyRoles(UUID clinicId, UUID actorMembershipId, Set<String> roleCodes, NotificationKind kind,
            Map<String, String> payload) {
        if (actorMembershipId == null || roleCodes == null || roleCodes.isEmpty() || kind == null) {
            throw new IllegalArgumentException("دور واحد على الأقل أو نوع الإشعار مطلوب");
        }
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            Map<String, String> enriched = enrichPayload(clinicId, actorMembershipId, kind, payload);
            validatePayloadForWrite(kind, enriched);
            var recipients = dsl.select(MEMBERSHIP.ID)
                    .from(MEMBERSHIP)
                    .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                    .and(ROLE.CODE.in(roleCodes))
                    .and(MEMBERSHIP.ID.ne(actorMembershipId))
                    .fetchSet(MEMBERSHIP.ID);
            recipients.forEach(id -> insert(clinicId, id, kind, enriched));
            return recipients.size();
        });
    }

    @Override
    public int notifyApprovers(UUID clinicId, UUID actorMembershipId, String managerPermission, NotificationKind kind,
            Map<String, String> payload) {
        if (actorMembershipId == null || kind == null) {
            throw new IllegalArgumentException("مرسل الإشعار أو نوعه مطلوب");
        }
        return transactionTemplate.execute(status -> {
            guardTenant(clinicId);
            Map<String, String> enriched = enrichPayload(clinicId, actorMembershipId, kind, payload);
            validatePayloadForWrite(kind, enriched);
            var candidates = dsl.selectFrom(MEMBERSHIP)
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                    .and(MEMBERSHIP.ID.ne(actorMembershipId))
                    .fetch();
            int sent = 0;
            for (var candidate : candidates) {
                String roleCode = dsl.select(ROLE.CODE)
                        .from(ROLE)
                        .where(ROLE.ID.eq(candidate.getRoleId()))
                        .fetchOne(ROLE.CODE);
                if ("owner".equals(roleCode)
                        || ("manager".equals(roleCode)
                                && (managerPermission == null || holdsPermission(candidate.getId(), managerPermission)))) {
                    insert(clinicId, candidate.getId(), kind, enriched);
                    sent++;
                }
            }
            return sent;
        });
    }

    private boolean holdsPermission(UUID membershipId, String permissionCode) {
        return dsl.fetchExists(dsl.selectOne()
                .from(PERMISSION)
                .where(PERMISSION.CODE.eq(permissionCode))
                .and(PERMISSION.ID.in(
                        dsl.select(ROLE_PERMISSION.PERMISSION_ID)
                                .from(ROLE_PERMISSION)
                                .join(MEMBERSHIP).on(MEMBERSHIP.ROLE_ID.eq(ROLE_PERMISSION.ROLE_ID))
                                .where(MEMBERSHIP.ID.eq(membershipId))
                                .union(dsl.select(MEMBERSHIP_PERMISSION.PERMISSION_ID)
                                        .from(MEMBERSHIP_PERMISSION)
                                        .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(membershipId))
                                        .and(MEMBERSHIP_PERMISSION.GRANTED.isTrue()))))
                .and(PERMISSION.ID.notIn(dsl.select(MEMBERSHIP_PERMISSION.PERMISSION_ID)
                        .from(MEMBERSHIP_PERMISSION)
                        .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(membershipId))
                        .and(MEMBERSHIP_PERMISSION.GRANTED.isFalse()))));
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
                    .set(NOTIFICATION.READ_AT, DSL.coalesce(NOTIFICATION.READ_AT, OffsetDateTime.now()))
                    .where(NOTIFICATION.ID.eq(notificationId))
                    .and(NOTIFICATION.CLINIC_ID.eq(clinicId))
                    .and(NOTIFICATION.RECIPIENT_MEMBERSHIP_ID.eq(membershipId))
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

    private Map<String, String> enrichPayload(UUID clinicId, UUID actorMembershipId, NotificationKind kind,
            Map<String, String> payload) {
        String actor = dsl.select(APP_USER.FULL_NAME)
                .from(MEMBERSHIP)
                .join(APP_USER).on(APP_USER.ID.eq(MEMBERSHIP.USER_ID))
                .where(MEMBERSHIP.ID.eq(actorMembershipId))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(APP_USER.CLINIC_ID.eq(clinicId))
                .fetchOne(APP_USER.FULL_NAME);
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("مرسل الإشعار غير موجود");
        }
        Map<String, String> enriched = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        enriched.put("actor", actor);
        if (kind == NotificationKind.INVENTORY_CHANGE_REQUESTED) {
            String changeKind = enriched.remove("changeKind");
            enriched.put("action", switch (changeKind == null ? "" : changeKind) {
                case "edit" -> "تعديل";
                case "delete" -> "حذف";
                default -> "";
            });
        }
        return enriched;
    }

    private void guardTenant(UUID clinicId) {
        if (!TenantContext.get().filter(clinicId::equals).isPresent()) {
            throw new IllegalStateException("No tenant bound matching clinic " + clinicId);
        }
    }

    private Notification toNotification(org.jooq.Record r) {
        NotificationKind kind = NotificationKind.fromLiteral(r.get(NOTIFICATION.KIND));
        Map<String, String> payload = parsePayload(r.get(NOTIFICATION.PAYLOAD));
        if (!kind.hasValidPayload(payload)) {
            throw new MalformedNotificationDataException("بيانات الإشعار غير مكتملة");
        }
        return new Notification(
                r.get(NOTIFICATION.ID),
                kind,
                payload,
                r.get(NOTIFICATION.CREATED_AT),
                r.get(NOTIFICATION.READ_AT));
    }

    private static void validatePayloadForWrite(NotificationKind kind, Map<String, String> payload) {
        if (!kind.hasValidPayload(payload)) {
            throw new IllegalArgumentException("بيانات الإشعار غير مكتملة");
        }
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
            Map<String, String> parsed = JSON.readValue(payload.data(), PAYLOAD_TYPE);
            if (parsed == null) {
                throw new MalformedNotificationDataException("تعذر عرض بيانات الإشعار");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new MalformedNotificationDataException("تعذر عرض بيانات الإشعار", e);
        }
    }
}
