package com.clinicos;

import static com.clinicos.shared.jooq.tables.AppUser.APP_USER;
import static com.clinicos.shared.jooq.tables.Clinic.CLINIC;
import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static com.clinicos.shared.jooq.tables.EvaluationWeight.EVALUATION_WEIGHT;
import static com.clinicos.shared.jooq.tables.IncentiveTier.INCENTIVE_TIER;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static org.jooq.impl.DSL.val;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import com.clinicos.shared.jooq.enums.EvalCategory;
import com.clinicos.shared.jooq.enums.MembershipStatus;

/**
 * Shared row-insert helpers for integration tests that seed clinic/user/
 * membership rows directly over JDBC (auth-mode and pre-tenant scenarios
 * that can't go through the app's normal tenant-bound repositories).
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static UUID insertClinic(Connection connection) throws Exception {
        return insertClinic(connection, "Test Clinic", "test-clinic-" + UUID.randomUUID());
    }

    public static UUID insertClinic(Connection connection, String name, String slug) throws Exception {
        DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
        UUID id = dsl.insertInto(CLINIC, CLINIC.NAME, CLINIC.SLUG)
                .values(name, slug)
                .returningResult(CLINIC.ID)
                .fetchOne(CLINIC.ID);
        dsl.insertInto(CLINIC_SETTINGS, CLINIC_SETTINGS.CLINIC_ID)
                .values(id)
                .execute();
        dsl.insertInto(EVALUATION_WEIGHT, EVALUATION_WEIGHT.CLINIC_ID, EVALUATION_WEIGHT.CATEGORY, EVALUATION_WEIGHT.WEIGHT)
                .values(id, EvalCategory.completion, new BigDecimal("18"))
                .values(id, EvalCategory.fanni, new BigDecimal("18"))
                .values(id, EvalCategory.solooki, new BigDecimal("12"))
                .values(id, EvalCategory.ibda3, new BigDecimal("22"))
                .values(id, EvalCategory.volume, new BigDecimal("18"))
                .values(id, EvalCategory.attendance, new BigDecimal("12"))
                .execute();
        dsl.insertInto(INCENTIVE_TIER, INCENTIVE_TIER.CLINIC_ID, INCENTIVE_TIER.NAME, INCENTIVE_TIER.MIN_SCORE, INCENTIVE_TIER.INCENTIVE_PCT)
                .values(id, "ممتاز", new BigDecimal("90"), new BigDecimal("100"))
                .values(id, "جيد جداً", new BigDecimal("75"), new BigDecimal("75"))
                .values(id, "جيد", new BigDecimal("60"), new BigDecimal("50"))
                .values(id, "يحتاج تطوير", new BigDecimal("0"), new BigDecimal("0"))
                .execute();
        return id;
    }

    public static UUID insertUser(Connection connection, UUID clinicId) throws Exception {
        return insertUser(connection, clinicId, "user-" + UUID.randomUUID(), "hash", "active", "Test User");
    }

    public static UUID insertUser(Connection connection, UUID clinicId, String username,
            String passwordHash, String status) throws Exception {
        return insertUser(connection, clinicId, username, passwordHash, status, "Test User");
    }

    public static UUID insertUser(Connection connection, UUID clinicId, String username,
            String passwordHash, String status, String fullName) throws Exception {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(APP_USER, APP_USER.CLINIC_ID, APP_USER.USERNAME, APP_USER.PASSWORD_HASH,
                        APP_USER.STATUS, APP_USER.FULL_NAME)
                .values(clinicId, username, passwordHash, status, fullName)
                .returningResult(APP_USER.ID)
                .fetchOne(APP_USER.ID);
    }

    public static UUID insertUserWithEmail(Connection connection, UUID clinicId, String username, String email,
            String passwordHash, String fullName) throws Exception {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(APP_USER, APP_USER.CLINIC_ID, APP_USER.USERNAME, APP_USER.EMAIL, APP_USER.PASSWORD_HASH,
                        APP_USER.FULL_NAME, APP_USER.STATUS)
                .values(clinicId, username, email, passwordHash, fullName, "active")
                .returningResult(APP_USER.ID)
                .fetchOne(APP_USER.ID);
    }

    public static UUID insertMembership(Connection connection, UUID clinicId, UUID userId) throws Exception {
        return insertMembership(connection, clinicId, userId, "owner");
    }

    public static UUID lookupUserId(Connection connection, UUID clinicId, String username) {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .select(APP_USER.ID)
                .from(APP_USER)
                .where(APP_USER.CLINIC_ID.eq(clinicId), APP_USER.USERNAME.eq(username))
                .fetchOne(APP_USER.ID);
    }

    public static UUID insertMembership(Connection connection, UUID clinicId, UUID userId, String roleCode) throws Exception {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(MEMBERSHIP, MEMBERSHIP.CLINIC_ID, MEMBERSHIP.USER_ID, MEMBERSHIP.ROLE_ID, MEMBERSHIP.STATUS)
                .select(DSL.select(val(clinicId), val(userId), ROLE.ID, val(MembershipStatus.active))
                        .from(ROLE)
                        .where(ROLE.CODE.eq(roleCode)))
                .returningResult(MEMBERSHIP.ID)
                .fetchOne(MEMBERSHIP.ID);
    }
}