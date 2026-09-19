package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.ActivityLog.ACTIVITY_LOG;
import static com.clinicos.shared.jooq.tables.AppUser.APP_USER;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
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

    public List<Entry> forDay(UUID clinicId, LocalDate day, String category) {
        OffsetDateTime from = day.atStartOfDay(ZoneId.of("Africa/Cairo")).toOffsetDateTime();
        OffsetDateTime until = day.plusDays(1).atStartOfDay(ZoneId.of("Africa/Cairo")).toOffsetDateTime();
        List<Entry> entries = transactionTemplate.execute(status -> dsl
                .select(ACTIVITY_LOG.OCCURRED_AT, APP_USER.FULL_NAME, ACTIVITY_LOG.ACTION,
                        ACTIVITY_LOG.ENTITY_TYPE)
                .from(ACTIVITY_LOG)
                .leftJoin(MEMBERSHIP).on(MEMBERSHIP.ID.eq(ACTIVITY_LOG.ACTOR_MEMBERSHIP_ID))
                .leftJoin(APP_USER).on(APP_USER.ID.eq(MEMBERSHIP.USER_ID))
                .where(ACTIVITY_LOG.CLINIC_ID.eq(clinicId))
                .and(ACTIVITY_LOG.OCCURRED_AT.ge(from))
                .and(ACTIVITY_LOG.OCCURRED_AT.lt(until))
                .orderBy(ACTIVITY_LOG.OCCURRED_AT.desc())
                .fetch(row -> new Entry(row.get(ACTIVITY_LOG.OCCURRED_AT), row.get(APP_USER.FULL_NAME),
                        row.get(ACTIVITY_LOG.ACTION), row.get(ACTIVITY_LOG.ENTITY_TYPE), category)));
        if (category == null || category.isBlank() || "all".equals(category)) {
            return entries;
        }
        List<String> actions = Arrays.stream(category.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return entries.stream().filter(entry -> actions.stream().anyMatch(action ->
                entry.action().equals(action) || entry.action().startsWith(action + "."))).toList();
    }

    public record Entry(OffsetDateTime occurredAt, String actorName, String action,
            String entityType, String categoryKey) {
    }
}
