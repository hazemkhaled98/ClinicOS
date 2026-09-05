package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.AppUser.APP_USER;
import static com.clinicos.shared.jooq.tables.Clinic.CLINIC;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

        assertThat(result.userId()).isNotNull();
        assertThat(result.clinicId()).isNotNull();
        assertThat(result.membershipId()).isNotNull();
        assertThat(result).satisfies(SignupResult::userId, SignupResult::clinicId, SignupResult::membershipId)
                .isNotNull();

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
    void duplicateUsernameSurfacesSignupConflictOnUsername() {
        String username = "duplicate-" + uniqueSuffix();
        String rawPassword = "correct-horse-battery-staple";

        signupService.signUp(new SignupRequest(
                "First Clinic " + uniqueSuffix(), "A", username, null, rawPassword));

        assertThatThrownBy(() -> signupService.signUp(new SignupRequest(
                "Second Clinic " + uniqueSuffix(), "B", username, null, rawPassword)))
                .isInstanceOfSatisfying(SignupConflictException.class,
                        conflict -> assertThat(conflict.getField()).isEqualTo(Field.USERNAME));
    }

    @Test
    void duplicateEmailSurfacesSignupConflictOnEmail() {
        String email = "owner-" + uniqueSuffix() + "@duplicate.example";

        signupService.signUp(new SignupRequest(
                "First Clinic " + uniqueSuffix(), "A", "user-a-" + uniqueSuffix(), email, "password-12345"));

        assertThatThrownBy(() -> signupService.signUp(new SignupRequest(
                "Second Clinic " + uniqueSuffix(), "B", "user-b-" + uniqueSuffix(), email, "password-12345")))
                .isInstanceOfSatisfying(SignupConflictException.class,
                        conflict -> assertThat(conflict.getField()).isEqualTo(Field.EMAIL));
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