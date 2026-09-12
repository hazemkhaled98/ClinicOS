package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;

import java.util.UUID;

import org.jooq.DSLContext;

final class RoleRanks {

    static final String OWNER = "owner";
    static final String MANAGER = "manager";

    private RoleRanks() {
    }

    static int of(String roleCode) {
        return switch (roleCode) {
            case OWNER -> 3;
            case MANAGER -> 2;
            default -> 1;
        };
    }

    static String ofMembership(DSLContext dsl, UUID clinicId, UUID membershipId) {
        String roleCode = dsl.select(ROLE.CODE)
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.ID.eq(membershipId))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .fetchOne(ROLE.CODE);
        if (roleCode == null) {
            throw new IllegalArgumentException("العضوية غير موجودة");
        }
        return roleCode;
    }
}
