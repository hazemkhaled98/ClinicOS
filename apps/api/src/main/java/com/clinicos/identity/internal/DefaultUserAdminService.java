package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.AppUser.APP_USER;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.Routines.createClinicUser;
import static com.clinicos.shared.jooq.Routines.setUserPassword;
import static com.clinicos.shared.jooq.Routines.setUserStatus;

import java.util.List;
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
            UUID userId;
            try {
                userId = createClinicUser(dsl.configuration(), clinicId,
                        request.username(), request.fullName(), request.passwordHash(), request.email());
            } catch (DuplicateKeyException e) {
                throw new IllegalArgumentException("اسم المستخدم موجود مسبقاً في هذه العيادة");
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
    public void changePassword(UUID clinicId, UUID userId, String newPasswordHash) {
        transactionTemplate.executeWithoutResult(status ->
                setUserPassword(dsl.configuration(), clinicId, userId, newPasswordHash));
    }

    @Override
    public void suspend(UUID clinicId, UUID userId, UUID actorMembershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            org.jooq.Record target = dsl.select(MEMBERSHIP.ID, ROLE.CODE)
                    .from(APP_USER)
                    .join(MEMBERSHIP).on(MEMBERSHIP.USER_ID.eq(APP_USER.ID)
                            .and(MEMBERSHIP.CLINIC_ID.eq(clinicId)))
                    .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                    .where(APP_USER.ID.eq(userId))
                    .and(APP_USER.CLINIC_ID.eq(clinicId))
                    .fetchOne();
            if (target == null) {
                throw new IllegalArgumentException("المستخدم غير موجود");
            }
            if (target.get(MEMBERSHIP.ID).equals(actorMembershipId)) {
                throw new IllegalArgumentException("لا يمكنك تعليق حسابك الخاص");
            }
            if ("owner".equals(target.get(ROLE.CODE))) {
                throw new IllegalArgumentException("تعليق حساب المالك من صلاحيات إدارة النظام فقط");
            }
            setUserStatus(dsl.configuration(), clinicId, userId, "suspended");
        });
    }

    @Override
    public void reactivate(UUID clinicId, UUID userId) {
        transactionTemplate.executeWithoutResult(status ->
                setUserStatus(dsl.configuration(), clinicId, userId, "active"));
    }

    @Override
    public void assignRole(UUID clinicId, UUID membershipId, String roleCode) {
        transactionTemplate.executeWithoutResult(status -> {
            UUID roleId = dsl.select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.CODE.eq(roleCode))
                    .fetchOne(ROLE.ID);
            if (roleId == null) {
                throw new IllegalArgumentException("الدور غير موجود: " + roleCode);
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