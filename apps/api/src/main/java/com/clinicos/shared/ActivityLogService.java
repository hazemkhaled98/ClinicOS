package com.clinicos.shared;

import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ActivityLogService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ActivityLogService(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = transactionTemplate;
    }

    public void log(UUID clinicId, UUID membershipId, String action, String entityType) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                    "insert into activity_log (clinic_id, actor_membership_id, action, entity_type) values (?, ?, ?, ?)",
                    clinicId, membershipId, action, entityType);
            return null;
        });
    }
}
