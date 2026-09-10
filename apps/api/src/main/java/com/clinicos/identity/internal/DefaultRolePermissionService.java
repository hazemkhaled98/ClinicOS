package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.Permission.PERMISSION;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.RolePermission.ROLE_PERMISSION;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.RolePermissionService;

@Service
public class DefaultRolePermissionService implements RolePermissionService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultRolePermissionService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
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
    public void setPermissions(UUID clinicId, String roleCode, Set<String> permissionCodes) {
        transactionTemplate.executeWithoutResult(status -> {
            UUID roleId = dsl.select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.CODE.eq(roleCode))
                    .fetchOne(ROLE.ID);
            if (roleId == null) {
                throw new IllegalArgumentException("الدور غير موجود: " + roleCode);
            }
            dsl.deleteFrom(ROLE_PERMISSION)
                    .where(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                    .and(ROLE_PERMISSION.CLINIC_ID.eq(clinicId))
                    .execute();
            for (String code : permissionCodes) {
                UUID permId = dsl.select(PERMISSION.ID)
                        .from(PERMISSION)
                        .where(PERMISSION.CODE.eq(code))
                        .fetchOne(PERMISSION.ID);
                if (permId == null) {
                    throw new IllegalArgumentException("الصلاحية غير موجودة: " + code);
                }
                dsl.insertInto(ROLE_PERMISSION)
                        .set(ROLE_PERMISSION.ROLE_ID, roleId)
                        .set(ROLE_PERMISSION.PERMISSION_ID, permId)
                        .set(ROLE_PERMISSION.CLINIC_ID, clinicId)
                        .execute();
            }
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
