package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.AppUser.APP_USER;
import static com.clinicos.shared.jooq.tables.Clinic.CLINIC;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Permission.PERMISSION;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.RolePermission.ROLE_PERMISSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.identity.api.SignupService.SignupConflictException;
import com.clinicos.identity.api.SignupService.SignupConflictException.Field;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.identity.api.SignupService.SignupResult;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.ClinicStatus;
import com.clinicos.shared.jooq.enums.MembershipStatus;

/**
 * UC-001 self-service sign-up, against the real V13 migration. Blackbox-only
 * for the DML itself: happy-path assertions read the resulting rows as the
 * migration superuser, never through the app's tenant connection.
 */
@SpringBootTest(classes = Application.class)
class SignupServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultSignupService signupService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DSLContext dsl;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void signupProvisionsClinicTrialUserActiveAndOwnerMembership() throws Exception {
        String rawPassword = "correct-horse-battery-staple";
        String suffix = uniqueSuffix();
        String clinicName = "Sunrise Dental " + suffix;
        String username = "owner-" + suffix;

        SignupResult result = signupService.signUp(new SignupRequest(
                clinicName, "Dr Ahmed", username, "owner@sunrise.example", rawPassword));

        assertThat(result).satisfies(SignupResult::userId, SignupResult::clinicId, SignupResult::membershipId,
                SignupResult::clinicSlug).isNotNull();
        assertThat(result.clinicSlug()).isEqualTo("sunrise-dental-" + suffix);

        try (Connection connection = superuserConnection()) {
            DSLContext superuserDsl = DSL.using(connection, SQLDialect.POSTGRES);
            assertThat(superuserDsl.fetchCount(CLINIC,
                    CLINIC.NAME.eq(clinicName).and(CLINIC.SLUG.eq("sunrise-dental-" + suffix)))).isEqualTo(1);
            assertThat(superuserDsl.select(CLINIC.STATUS).from(CLINIC).where(CLINIC.NAME.eq(clinicName))
                    .fetchOne(CLINIC.STATUS)).isEqualTo(ClinicStatus.trial);

            assertThat(superuserDsl.fetchCount(APP_USER,
                    APP_USER.USERNAME.eq(username).and(APP_USER.STATUS.eq("active")))).isEqualTo(1);
            String hash = superuserDsl.select(APP_USER.PASSWORD_HASH).from(APP_USER)
                    .where(APP_USER.USERNAME.eq(username)).fetchOne(APP_USER.PASSWORD_HASH);
            assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();

            assertThat(superuserDsl.fetchCount(MEMBERSHIP,
                    MEMBERSHIP.USER_ID.eq(superuserDsl.select(APP_USER.ID).from(APP_USER)
                            .where(APP_USER.USERNAME.eq(username)))
                            .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                            .and(MEMBERSHIP.ROLE_ID.eq(
                                    superuserDsl.select(ROLE.ID).from(ROLE).where(ROLE.CODE.eq("owner"))))))
                    .isEqualTo(1);
        }
    }

    @Test
    void BRG01_signupSeedsRoleGrantsScopedToTheBusinessRule() throws Exception {
        String suffix = uniqueSuffix();

        SignupResult result = signupService.signUp(new SignupRequest(
                "Scoped Grants " + suffix, "Dr Ahmed", "owner-" + suffix, "owner@scoped.example",
                "correct-horse-battery-staple"));

        try (Connection connection = superuserConnection()) {
            DSLContext superuserDsl = DSL.using(connection, SQLDialect.POSTGRES);
            assertThat(grantedCodes(superuserDsl, result.clinicId(), "receptionist"))
                    .contains("orders", "receive", "returns", "suppliers")
                    .doesNotContain("ledger", "tray", "issue");
            assertThat(grantedCodes(superuserDsl, result.clinicId(), "assistant"))
                    .contains("tray", "issue", "myprocs", "manage")
                    .doesNotContain("procs", "ledger");
            assertThat(grantedCodes(superuserDsl, result.clinicId(), "manager"))
                    .contains("approvals", "analytics", "ledger");
        }
    }

    private Set<String> grantedCodes(DSLContext dsl, UUID clinicId, String roleCode) {
        return Set.copyOf(dsl.select(PERMISSION.CODE).from(ROLE_PERMISSION)
                .join(ROLE).on(ROLE_PERMISSION.ROLE_ID.eq(ROLE.ID))
                .join(PERMISSION).on(ROLE_PERMISSION.PERMISSION_ID.eq(PERMISSION.ID))
                .where(ROLE_PERMISSION.CLINIC_ID.eq(clinicId))
                .and(ROLE.CODE.eq(roleCode))
                .fetch(PERMISSION.CODE));
    }

    @Test
    void sameUsernameInTwoDifferentClinicsSucceeds() {
        String username = "shared-" + uniqueSuffix();
        String rawPassword = "correct-horse-battery-staple";

        SignupResult first = signupService.signUp(new SignupRequest(
                "First Clinic " + uniqueSuffix(), "A", username, null, rawPassword));
        SignupResult second = signupService.signUp(new SignupRequest(
                "Second Clinic " + uniqueSuffix(), "B", username, null, rawPassword));

        assertThat(first.clinicId()).isNotNull();
        assertThat(second.clinicId()).isNotNull();
        assertThat(first.clinicId()).isNotEqualTo(second.clinicId());
        assertThat(first.clinicSlug()).isNotEqualTo(second.clinicSlug());
    }

    @Test
    void duplicateUsernameWithinOneClinicIsRejectedByTheDatabase() throws Exception {
        String suffix = uniqueSuffix();
        String clinicName = "Duplicate Clinic " + suffix;
        SignupResult first = signupService.signUp(new SignupRequest(
                clinicName, "A", "duplicate-" + suffix, null, "password-12345"));

try (Connection connection = superuserConnection()) {
            DSLContext superuserDsl = DSL.using(connection, SQLDialect.POSTGRES);
            assertThatThrownBy(() -> superuserDsl.insertInto(APP_USER,
                    APP_USER.CLINIC_ID, APP_USER.USERNAME, APP_USER.PASSWORD_HASH, APP_USER.STATUS,
                    APP_USER.FULL_NAME)
                    .values(first.clinicId(), "duplicate-" + suffix, "hash", "active", "Direct Insert")
                    .execute())
                    .isInstanceOfSatisfying(IntegrityConstraintViolationException.class,
                            e -> assertThat(e.getMessage())
                                    .contains("app_user_clinic_username_key"));
        }
    }

    @Test
    void duplicateEmailWithinOneClinicIsRejectedByTheDatabase() throws Exception {
        String suffix = uniqueSuffix();
        String email = "owner-" + suffix + "@duplicate.example";
        SignupResult first = signupService.signUp(new SignupRequest(
                "Duplicate Clinic " + suffix, "A", "user-a-" + suffix, email, "password-12345"));

        try (Connection connection = superuserConnection()) {
            DSLContext superuserDsl = DSL.using(connection, SQLDialect.POSTGRES);
            assertThatThrownBy(() -> superuserDsl.insertInto(APP_USER,
                    APP_USER.CLINIC_ID, APP_USER.USERNAME, APP_USER.PASSWORD_HASH, APP_USER.STATUS,
                    APP_USER.FULL_NAME, APP_USER.EMAIL)
                    .values(first.clinicId(), "user-b-" + suffix, "hash", "active", "Direct Insert", email)
                    .execute())
                    .isInstanceOfSatisfying(IntegrityConstraintViolationException.class,
                            e -> assertThat(e.getMessage())
                                    .contains("idx_app_user_email_when_not_null"));
        }
    }

    @Test
    void slugCollisionIsRetriedOnceWithRandomSuffix() throws Exception {
        String suffix = uniqueSuffix();
        String clinicName = "Same Name " + suffix;
        String rawPassword = "correct-horse-battery-staple";

        SignupResult first = signupService.signUp(new SignupRequest(
                clinicName, "A", "user-a-" + suffix, null, rawPassword));
        SignupResult second = signupService.signUp(new SignupRequest(
                clinicName, "B", "user-b-" + suffix, null, rawPassword));

        assertThat(first.clinicId()).isNotEqualTo(second.clinicId());
        try (Connection connection = superuserConnection()) {
            DSLContext superuserDsl = DSL.using(connection, SQLDialect.POSTGRES);
            List<String> slugs = superuserDsl.select(CLINIC.SLUG).from(CLINIC)
                    .where(CLINIC.ID.in(first.clinicId(), second.clinicId()))
                    .orderBy(CLINIC.CREATED_AT)
                    .fetch(CLINIC.SLUG);
            assertThat(slugs).hasSize(2);
            assertThat(slugs.get(0)).isEqualTo("same-name-" + suffix);
            assertThat(slugs.get(1)).startsWith("same-name-" + suffix + "-");
            assertThat(slugs.get(1)).hasSizeGreaterThan(slugs.get(0).length());
        }
    }

    @Test
    void exhaustedSlugRetrySurfacesSignupConflictOnClinicSlug() throws Exception {
        String suffix = uniqueSuffix();
        String clinicName = "Occupied Name " + suffix;
        String derivedSlug = "occupied-name-" + suffix;
        String retriedSlug = derivedSlug + "-cafebabe";
        String rawPassword = "correct-horse-battery-staple";

        try (Connection connection = superuserConnection()) {
            insertClinicRow(connection, clinicName, derivedSlug);
            insertClinicRow(connection, clinicName, retriedSlug);
        }

        try (MockedStatic<DefaultSignupService> mockedStatic = mockStatic(DefaultSignupService.class)) {
            mockedStatic.when(() -> DefaultSignupService.deriveSlug(org.mockito.ArgumentMatchers.anyString()))
                    .thenCallRealMethod();
            mockedStatic.when(DefaultSignupService::randomSuffix).thenReturn("cafebabe");

            assertThatThrownBy(() -> signupService.signUp(new SignupRequest(
                    clinicName, "A", "user-" + suffix, null, rawPassword)))
                    .isInstanceOfSatisfying(SignupConflictException.class,
                            conflict -> assertThat(conflict.getField()).isEqualTo(Field.CLINIC_SLUG));
        }
    }

    @Test
    void directClinicInsertStillFailsForAppRwWithoutTenant() {
        String suffix = uniqueSuffix();

        assertThatThrownBy(() -> dsl.insertInto(CLINIC, CLINIC.NAME, CLINIC.SLUG)
                .values("Direct Insert", "direct-" + suffix)
                .execute())
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(e.getMostSpecificCause().getMessage())
                                .contains("permission denied for table clinic"));
    }

    @Test
    void failedSignupRollsBackTheWholeClinic() throws Exception {
        String suffix = uniqueSuffix();
        String clinicName = "Rollback Clinic " + suffix;
        String username = "rollback-" + suffix;

        try (Connection connection = superuserConnection()) {
            DSLContext superuserDsl = DSL.using(connection, SQLDialect.POSTGRES);
            superuserDsl.query("create function test_abort_signup() returns trigger language plpgsql as "
                    + "$$ begin raise exception 'deliberate signup failure'; end $$")
                    .execute();
            superuserDsl.query("create trigger test_abort_signup_trigger before insert on membership "
                    + "for each row execute function test_abort_signup()")
                    .execute();
        }
        try {
            assertThatThrownBy(() -> signupService.signUp(new SignupRequest(
                    clinicName, "A", username, null, "password-12345")))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class);
        } finally {
            try (Connection connection = superuserConnection()) {
                DSL.using(connection, SQLDialect.POSTGRES)
                        .query("drop trigger if exists test_abort_signup_trigger on membership")
                        .execute();
                DSL.using(connection, SQLDialect.POSTGRES)
                        .query("drop function if exists test_abort_signup()")
                        .execute();
            }
        }

        try (Connection connection = superuserConnection()) {
            DSLContext superuserDsl = DSL.using(connection, SQLDialect.POSTGRES);
            assertThat(superuserDsl.fetchCount(CLINIC, CLINIC.NAME.eq(clinicName))).isZero();
            assertThat(superuserDsl.fetchCount(APP_USER, APP_USER.USERNAME.eq(username))).isZero();
        }
    }

    private void insertClinicRow(Connection connection, String name, String slug) {
        DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(CLINIC, CLINIC.NAME, CLINIC.SLUG)
                .values(name, slug)
                .execute();
    }

    private Connection superuserConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}