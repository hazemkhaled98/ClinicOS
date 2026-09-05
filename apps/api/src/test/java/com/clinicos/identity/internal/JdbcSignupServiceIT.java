package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.identity.api.SignupService.SignupConflictException;
import com.clinicos.identity.api.SignupService.SignupConflictException.Field;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.identity.api.SignupService.SignupResult;
import com.clinicos.shared.TenantContext;

/**
 * UC-001 self-service sign-up, against the real V13 migration. Blackbox-only
 * for the DML itself: happy-path assertions read the resulting rows as the
 * migration superuser, never through the app's tenant connection.
 */
@SpringBootTest(classes = Application.class)
class JdbcSignupServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcSignupService signupService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

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
            assertThat(count(connection, "clinic", "where name = ? and slug = ?",
                    clinicName, "sunrise-dental-" + suffix)).isEqualTo(1);
            assertThat(queryString(connection, "select status from clinic where name = ?", clinicName))
                    .isEqualTo("trial");

            assertThat(count(connection, "app_user", "where username = ? and status = 'active'", username))
                    .isEqualTo(1);
            String hash = queryString(connection, "select password_hash from app_user where username = ?", username);
            assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();

            assertThat(count(connection, "membership",
                    "where user_id = (select id from app_user where username = ?) "
                            + "and status = 'active' and role_id = (select id from role where code = 'owner')",
                    username)).isEqualTo(1);
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
            try (PreparedStatement statement = connection.prepareStatement(
                    "select slug from clinic where id in (?, ?) order by created_at")) {
                statement.setObject(1, first.clinicId());
                statement.setObject(2, second.clinicId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    String firstSlug = resultSet.getString(1);
                    resultSet.next();
                    String secondSlug = resultSet.getString(1);
                    assertThat(firstSlug).isEqualTo("same-name-" + suffix);
                    assertThat(secondSlug).startsWith("same-name-" + suffix + "-");
                    assertThat(secondSlug).hasSizeGreaterThan(firstSlug.length());
                }
            }
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

        try (MockedStatic<JdbcSignupService> mockedStatic = mockStatic(JdbcSignupService.class)) {
            mockedStatic.when(() -> JdbcSignupService.deriveSlug(org.mockito.ArgumentMatchers.anyString()))
                    .thenCallRealMethod();
            mockedStatic.when(JdbcSignupService::randomSuffix).thenReturn("cafebabe");

            assertThatThrownBy(() -> signupService.signUp(new SignupRequest(
                    clinicName, "A", "user-" + suffix, null, rawPassword)))
                    .isInstanceOfSatisfying(SignupConflictException.class,
                            conflict -> assertThat(conflict.getField()).isEqualTo(Field.CLINIC_SLUG));
        }
    }

    @Test
    void directClinicInsertStillFailsForAppRwWithoutTenant() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String suffix = uniqueSuffix();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into clinic (name, slug) values (?, ?)", "Direct Insert", "direct-" + suffix))
                .isInstanceOfSatisfying(DataAccessException.class,
                        e -> assertThat(e.getMostSpecificCause().getMessage())
                                .contains("permission denied for table clinic"));
    }

    private void insertClinicRow(Connection connection, String name, String slug) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into clinic (name, slug) values (?, ?)")) {
            statement.setString(1, name);
            statement.setString(2, slug);
            statement.executeUpdate();
        }
    }

    private long count(Connection connection, String table, String whereClause, String... params) throws Exception {
        StringBuilder sql = new StringBuilder("select count(*) from ").append(table).append(' ').append(whereClause);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private String queryString(Connection connection, String sql, String param) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, param);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private Connection superuserConnection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}