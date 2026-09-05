package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.ActivityLog.ACTIVITY_LOG;

import java.util.UUID;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes activity-log entries. A write failure here is never allowed to
 * block the caller's own flow (e.g. login/clinic selection) -- the activity
 * log is a record of what happened, not a gate on whether it's allowed to
 * happen, so failures are logged and swallowed rather than propagated.
 */
@Service
public class ActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogService.class);

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public ActivityLogService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    public void log(UUID clinicId, UUID membershipId, String action, String entityType) {
        try {
            transactionTemplate.execute(status -> {
                dsl.insertInto(ACTIVITY_LOG)
                        .set(ACTIVITY_LOG.CLINIC_ID, clinicId)
                        .set(ACTIVITY_LOG.ACTOR_MEMBERSHIP_ID, membershipId)
                        .set(ACTIVITY_LOG.ACTION, action)
                        .set(ACTIVITY_LOG.ENTITY_TYPE, entityType)
                        .execute();
                return null;
            });
        } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
            log.error("Failed to write activity log entry: clinic={} membership={} action={} entityType={}",
                    clinicId, membershipId, action, entityType, e);
        }
    }
}