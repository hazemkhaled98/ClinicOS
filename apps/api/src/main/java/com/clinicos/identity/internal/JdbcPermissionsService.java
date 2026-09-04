package com.clinicos.identity.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
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

    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;

    public JdbcPermissionsService(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.dataSource = dataSource;
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
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement(
                "select r.code from membership m join role r on r.id = m.role_id where m.id = ?")) {
            statement.setObject(1, membershipId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                }
                throw new IllegalArgumentException("Unknown membership id: " + membershipId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve role for membership " + membershipId, e);
        }
    }

    private Set<String> effectivePermissionCodes(UUID membershipId) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement(
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
                """)) {
            statement.setObject(1, membershipId);
            statement.setObject(2, membershipId);
            statement.setObject(3, membershipId);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> codes = new HashSet<>();
                while (resultSet.next()) {
                    codes.add(resultSet.getString(1));
                }
                return codes;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve permissions for membership " + membershipId, e);
        }
    }

    private Set<String> allPermissionCodes() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement("select code from permission");
                ResultSet resultSet = statement.executeQuery()) {
            Set<String> codes = new HashSet<>();
            while (resultSet.next()) {
                codes.add(resultSet.getString(1));
            }
            return codes;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load full permission catalog", e);
        }
    }
}