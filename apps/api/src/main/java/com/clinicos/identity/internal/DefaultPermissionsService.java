package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.MembershipPermission.MEMBERSHIP_PERMISSION;
import static com.clinicos.shared.jooq.tables.Permission.PERMISSION;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.RolePermission.ROLE_PERMISSION;

import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.PermissionsService;

/**
 * Reads the effective permission set for a membership directly from the
 * database. Runs on the tenant connection ({@code app_rw}), so row-level
 * security already restricts {@code membership} and
 * {@code membership_permission} to the bound clinic; {@code role},
 * {@code permission} and {@code role_permission} are platform tables with
 * SELECT granted to {@code app_rw}.
 *
 * <p>The {@code owner} role bypasses every other setting: it receives the full
 * permission catalog even if a {@code membership_permission} row revokes one
 * of its codes (BR-G03).
 */
@Service
public class DefaultPermissionsService implements PermissionsService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultPermissionsService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public MembershipAccess accessFor(UUID membershipId) {
        return transactionTemplate.execute(status -> {
            String roleCode = roleCodeFor(membershipId);
            Set<String> codes = "owner".equals(roleCode)
                    ? allPermissionCodes()
                    : effectivePermissionCodes(membershipId);
            return new MembershipAccess(membershipId, roleCode, codes);
        });
    }

    private String roleCodeFor(UUID membershipId) {
        return dsl.select(ROLE.CODE)
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.ID.eq(membershipId))
                .fetchOptional(ROLE.CODE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown membership id: " + membershipId));
    }

    private Set<String> effectivePermissionCodes(UUID membershipId) {
        return dsl.select(PERMISSION.CODE)
                .from(PERMISSION)
                .where(PERMISSION.ID.in(
                        dsl.select(ROLE_PERMISSION.PERMISSION_ID)
                                .from(ROLE_PERMISSION)
                                .join(MEMBERSHIP).on(MEMBERSHIP.ROLE_ID.eq(ROLE_PERMISSION.ROLE_ID))
                                .where(MEMBERSHIP.ID.eq(membershipId))
                                .union(
                                        dsl.select(MEMBERSHIP_PERMISSION.PERMISSION_ID)
                                                .from(MEMBERSHIP_PERMISSION)
                                                .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(membershipId))
                                                .and(MEMBERSHIP_PERMISSION.GRANTED.isTrue()))))
                .and(PERMISSION.ID.notIn(
                        dsl.select(MEMBERSHIP_PERMISSION.PERMISSION_ID)
                                .from(MEMBERSHIP_PERMISSION)
                                .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(membershipId))
                                .and(MEMBERSHIP_PERMISSION.GRANTED.isFalse())))
                .fetchSet(PERMISSION.CODE);
    }

    private Set<String> allPermissionCodes() {
        return dsl.select(PERMISSION.CODE).from(PERMISSION).fetchSet(PERMISSION.CODE);
    }
}