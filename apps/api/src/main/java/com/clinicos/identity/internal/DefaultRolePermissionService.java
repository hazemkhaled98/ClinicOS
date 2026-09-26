package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.Permission.PERMISSION;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.RolePermission.ROLE_PERMISSION;
import static org.jooq.impl.DSL.val;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.RolePermissionService;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;

@Service
public class DefaultRolePermissionService implements RolePermissionService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final NotificationService notificationService;

    public DefaultRolePermissionService(DSLContext dsl, TransactionTemplate transactionTemplate,
            NotificationService notificationService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.notificationService = notificationService;
    }

    @Override
    public List<RolePermissionRow> listForClinic(UUID clinicId) {
        return transactionTemplate.execute(status ->
                dsl.select(ROLE.CODE, PERMISSION.CODE)
                        .from(ROLE_PERMISSION)
                        .join(ROLE).on(ROLE.ID.eq(ROLE_PERMISSION.ROLE_ID))
                        .join(PERMISSION).on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
                        .where(ROLE_PERMISSION.CLINIC_ID.eq(clinicId))
                        .fetch(r -> new RolePermissionRow(r.get(ROLE.CODE), r.get(PERMISSION.CODE))));
    }

    @Override
    public void setPermissions(UUID clinicId, UUID actorMembershipId, String roleCode, Set<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            throw new IllegalArgumentException("يجب تحديد صلاحية واحدة على الأقل");
        }
        transactionTemplate.executeWithoutResult(status -> {
            String actorRole = RoleRanks.ofMembership(dsl, clinicId, actorMembershipId);
            if (!RoleRanks.OWNER.equals(actorRole)
                    && RoleRanks.of(roleCode) >= RoleRanks.of(actorRole)) {
                throw new IllegalArgumentException("لا يمكنك تعديل صلاحيات دور أعلى أو مساوٍ لدورك");
            }
            UUID roleId = dsl.select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.CODE.eq(roleCode))
                    .fetchOne(ROLE.ID);
            if (roleId == null) {
                throw new IllegalArgumentException("الدور غير موجود: " + roleCode);
            }
            long found = dsl.selectCount()
                    .from(PERMISSION)
                    .where(PERMISSION.CODE.in(permissionCodes))
                    .fetchOneInto(Long.class);
            if (found != permissionCodes.size()) {
                throw new IllegalArgumentException("إحدى الصلاحيات غير موجودة");
            }
            dsl.deleteFrom(ROLE_PERMISSION)
                    .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                    .and(ROLE_PERMISSION.CLINIC_ID.eq(clinicId))
                    .execute();
            dsl.insertInto(ROLE_PERMISSION, ROLE_PERMISSION.ROLE_ID, ROLE_PERMISSION.PERMISSION_ID, ROLE_PERMISSION.CLINIC_ID)
                    .select(dsl.select(val(roleId), PERMISSION.ID, val(clinicId))
                            .from(PERMISSION)
                            .where(PERMISSION.CODE.in(permissionCodes)))
                    .execute();
            notificationService.notifyRoles(clinicId, actorMembershipId, Set.of("owner"),
                    NotificationKind.USER_ACCESS_CHANGED, Map.of("user", "الدور " + roleCode));
        });
    }

    @Override
    public Set<String> effectiveCodes(UUID clinicId, String roleCode) {
        return new HashSet<>(transactionTemplate.execute(status ->
                dsl.select(PERMISSION.CODE)
                        .from(ROLE_PERMISSION)
                        .join(ROLE).on(ROLE.ID.eq(ROLE_PERMISSION.ROLE_ID))
                        .join(PERMISSION).on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
                        .where(ROLE_PERMISSION.CLINIC_ID.eq(clinicId))
                        .and(ROLE.CODE.eq(roleCode))
                        .fetch(PERMISSION.CODE)));
    }
}
