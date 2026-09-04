package com.clinicos.identity.internal;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class JdbcPermissionsService implements PermissionsService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcPermissionsService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        try {
            return jdbcTemplate.queryForObject(
                    "select r.code from membership m join role r on r.id = m.role_id where m.id = ?",
                    String.class,
                    membershipId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Unknown membership id: " + membershipId);
        }
    }

    private Set<String> effectivePermissionCodes(UUID membershipId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                """
                select p.code
                from permission p
                where p.id in (
                    select rp.permission_id from role_permission rp
                    join membership m on m.role_id = rp.role_id and m.id = ?
                    union
                    select mp.permission_id from membership_permission mp
                    where mp.membership_id = ? and mp.granted
                )
                and p.id not in (
                    select mp.permission_id from membership_permission mp
                    where mp.membership_id = ? and mp.granted = false
                )
                """,
                String.class,
                membershipId, membershipId, membershipId));
    }

    private Set<String> allPermissionCodes() {
        return new HashSet<>(jdbcTemplate.queryForList("select code from permission", String.class));
    }
}
