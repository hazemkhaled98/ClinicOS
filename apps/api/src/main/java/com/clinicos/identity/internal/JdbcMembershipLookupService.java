package com.clinicos.identity.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.shared.TenantContext;

@Service
public class JdbcMembershipLookupService implements MembershipLookupService {

    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;

    public JdbcMembershipLookupService(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.dataSource = dataSource;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Membership> findByUserId(UUID userId) {
        return transactionTemplate.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select membership_id, clinic_id, clinic_name, role_code from app_user_memberships_lookup(?)")) {
                statement.setObject(1, userId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Membership> memberships = new ArrayList<>();
                    while (resultSet.next()) {
                        memberships.add(new Membership(
                                (UUID) resultSet.getObject(1),
                                (UUID) resultSet.getObject(2),
                                resultSet.getString(3),
                                resultSet.getString(4)));
                    }
                    return memberships;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to look up memberships for user: " + userId, e);
            }
        });
    }
}
