package com.clinicos.shared;

import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Service
public class ActivityLogService {

    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;

    public ActivityLogService(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.dataSource = dataSource;
        this.transactionTemplate = transactionTemplate;
    }

    public void log(UUID clinicId, UUID membershipId, String action, String entityType) {
        transactionTemplate.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into activity_log (clinic_id, actor_membership_id, action, entity_type) values (?, ?, ?, ?)")) {
                statement.setObject(1, clinicId);
                statement.setObject(2, membershipId);
                statement.setString(3, action);
                statement.setString(4, entityType);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to write activity log", e);
            }
            return null;
        });
    }
}
