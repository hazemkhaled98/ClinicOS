package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.AppUser.APP_USER;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.Routines.createClinicUser;
import static com.clinicos.shared.jooq.Routines.setUserPassword;
import static com.clinicos.shared.jooq.Routines.setUserStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.UserAdminService;

@Service
public class DefaultUserAdminService implements UserAdminService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultUserAdminService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<UserSummary> list(UUID clinicId) {
        return transactionTemplate.execute(status ->
                dsl.select(
                                APP_USER.ID, APP_USER.USERNAME, APP_USER.FULL_NAME, APP_USER.EMAIL,
                                APP_USER.STATUS, ROLE.CODE, MEMBERSHIP.ID, MEMBERSHIP.EMPLOYEE_ID)
                        .from(APP_USER)
                        .join(MEMBERSHIP).on(MEMBERSHIP.USER_ID.eq(APP_USER.ID)
                                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId)))
                        .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                        .where(APP_USER.CLINIC_ID.eq(clinicId))
                        .fetch(this::toSummary));
    }

    @Override
    public UserSummary create(UUID clinicId, UserCreateRequest request) {
        return transactionTemplate.execute(status -> {
            Map<String, String> fieldErrors = new HashMap<>();
            if (usernameExists(clinicId, request.username())) {
                fieldErrors.put("username", "اسم المستخدم موجود مسبقاً في هذه العيادة");
            }
            if (request.email() != null && emailExists(clinicId, request.email())) {
                fieldErrors.put("email", "البريد الإلكتروني مستخدم بالفعل في هذه العيادة");
            }
            if (!fieldErrors.isEmpty()) {
                throw new UserValidationException(fieldErrors);
            }
            UUID userId;
            try {
                userId = createClinicUser(dsl.configuration(), clinicId,
                        request.username(), request.fullName(), request.passwordHash(), request.email());
            } catch (DuplicateKeyException e) {
                throw conflictByConstraint(e);
            }
            UserSummary summary = dsl.select(
                        APP_USER.ID, APP_USER.USERNAME, APP_USER.FULL_NAME, APP_USER.EMAIL,
                        APP_USER.STATUS, ROLE.CODE, MEMBERSHIP.ID, MEMBERSHIP.EMPLOYEE_ID)
                .from(APP_USER)
                .join(MEMBERSHIP).on(MEMBERSHIP.USER_ID.eq(APP_USER.ID)
                        .and(MEMBERSHIP.CLINIC_ID.eq(clinicId)))
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(APP_USER.ID.eq(userId))
                .fetchOne(this::toSummary);
            if (summary == null) {
                throw new IllegalArgumentException("فشل إنشاء المستخدم");
            }
            return summary;
        });
    }

    @Override
    public void changePassword(UUID clinicId, UUID userId, String newPasswordHash, UUID actorMembershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            Target target = resolveTarget(clinicId, userId);
            String actorRole = RoleRanks.ofMembership(dsl, clinicId, actorMembershipId);
            if (!RoleRanks.OWNER.equals(actorRole)
                    && !target.membershipId().equals(actorMembershipId)
                    && RoleRanks.of(target.roleCode()) >= RoleRanks.of(actorRole)) {
                throw new IllegalArgumentException("لا يمكنك تغيير كلمة مرور حساب بدور أعلى أو مساوٍ لدورك");
            }
            setUserPassword(dsl.configuration(), clinicId, userId, newPasswordHash);
        });
    }

    @Override
    public void suspend(UUID clinicId, UUID userId, UUID actorMembershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            Target target = resolveTarget(clinicId, userId);
            if (target.membershipId().equals(actorMembershipId)) {
                throw new IllegalArgumentException("لا يمكنك تعليق حسابك الخاص");
            }
            if (RoleRanks.OWNER.equals(target.roleCode())) {
                throw new IllegalArgumentException("تعليق حساب المالك من صلاحيات إدارة النظام فقط");
            }
            String actorRole = RoleRanks.ofMembership(dsl, clinicId, actorMembershipId);
            if (RoleRanks.of(target.roleCode()) >= RoleRanks.of(actorRole)) {
                throw new IllegalArgumentException("لا يمكنك تعليق حساب بدور أعلى أو مساوٍ لدورك");
            }
            setUserStatus(dsl.configuration(), clinicId, userId, "suspended");
        });
    }

    @Override
    public void reactivate(UUID clinicId, UUID userId, UUID actorMembershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            Target target = resolveTarget(clinicId, userId);
            String actorRole = RoleRanks.ofMembership(dsl, clinicId, actorMembershipId);
            if (RoleRanks.of(target.roleCode()) >= RoleRanks.of(actorRole)) {
                throw new IllegalArgumentException("لا يمكنك تفعيل حساب بدور أعلى أو مساوٍ لدورك");
            }
            setUserStatus(dsl.configuration(), clinicId, userId, "active");
        });
    }

    @Override
    public void assignRole(UUID clinicId, UUID membershipId, String roleCode, UUID actorMembershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            UUID roleId = dsl.select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.CODE.eq(roleCode))
                    .fetchOne(ROLE.ID);
            if (roleId == null) {
                throw new IllegalArgumentException("الدور غير موجود: " + roleCode);
            }
            if (RoleRanks.OWNER.equals(roleCode)) {
                throw new IllegalArgumentException("لا يمكن تعيين دور المالك");
            }
            String targetRole = RoleRanks.ofMembership(dsl, clinicId, membershipId);
            String actorRole = RoleRanks.ofMembership(dsl, clinicId, actorMembershipId);
            if (!RoleRanks.OWNER.equals(actorRole)) {
                if (RoleRanks.of(targetRole) >= RoleRanks.of(actorRole)) {
                    throw new IllegalArgumentException("لا يمكنك تغيير دور حساب بدور أعلى أو مساوٍ لدورك");
                }
                if (RoleRanks.of(roleCode) >= RoleRanks.of(actorRole)) {
                    throw new IllegalArgumentException("لا يمكنك تعيين دور أعلى أو مساوٍ لدورك");
                }
            }
            int updated = dsl.update(MEMBERSHIP)
                    .set(MEMBERSHIP.ROLE_ID, roleId)
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("العضوية غير موجودة");
            }
        });
    }

    @Override
    public void linkEmployee(UUID clinicId, UUID membershipId, UUID employeeId) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = dsl.update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, employeeId)
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("العضوية غير موجودة");
            }
        });
    }

    private Target resolveTarget(UUID clinicId, UUID userId) {
        org.jooq.Record row = dsl.select(MEMBERSHIP.ID, ROLE.CODE)
                .from(APP_USER)
                .join(MEMBERSHIP).on(MEMBERSHIP.USER_ID.eq(APP_USER.ID)
                        .and(MEMBERSHIP.CLINIC_ID.eq(clinicId)))
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(APP_USER.ID.eq(userId))
                .and(APP_USER.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (row == null) {
            throw new IllegalArgumentException("المستخدم غير موجود");
        }
        return new Target(userId, row.get(MEMBERSHIP.ID), row.get(ROLE.CODE));
    }

    private record Target(UUID userId, UUID membershipId, String roleCode) {
    }

    private static UserValidationException conflictByConstraint(DuplicateKeyException e) {
        String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        if (message != null && message.contains("idx_app_user_email_when_not_null")) {
            return new UserValidationException(Map.of("email", "البريد الإلكتروني مستخدم بالفعل في هذه العيادة"));
        }
        return new UserValidationException(Map.of("username", "اسم المستخدم موجود مسبقاً في هذه العيادة"));
    }

    private boolean usernameExists(UUID clinicId, String username) {
        return dsl.fetchCount(APP_USER, APP_USER.CLINIC_ID.eq(clinicId)
                .and(APP_USER.USERNAME.eq(username))) > 0;
    }

    private boolean emailExists(UUID clinicId, String email) {
        return dsl.fetchCount(APP_USER, APP_USER.CLINIC_ID.eq(clinicId)
                .and(APP_USER.EMAIL.eq(email))) > 0;
    }

    private UserSummary toSummary(org.jooq.Record r) {
        return new UserSummary(
                r.get(APP_USER.ID),
                r.get(APP_USER.USERNAME),
                r.get(APP_USER.FULL_NAME),
                r.get(APP_USER.EMAIL),
                r.get(APP_USER.STATUS),
                r.get(ROLE.CODE),
                r.get(MEMBERSHIP.ID),
                r.get(MEMBERSHIP.EMPLOYEE_ID));
    }
}