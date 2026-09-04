package com.clinicos.identity.internal;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.identity.api.MembershipLookupService;

@Service
public class JdbcMembershipLookupService implements MembershipLookupService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcMembershipLookupService(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Membership> findByUserId(UUID userId) {
        return transactionTemplate.execute(status -> jdbcTemplate.query(
                "select membership_id, clinic_id, clinic_name, role_code from app_user_memberships_lookup(?)",
                (resultSet, rowNum) -> new Membership(
                        (UUID) resultSet.getObject(1),
                        (UUID) resultSet.getObject(2),
                        resultSet.getString(3),
                        resultSet.getString(4)),
                userId));
    }
}
