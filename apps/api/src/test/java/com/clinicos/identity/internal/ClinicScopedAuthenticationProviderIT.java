package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.shared.TenantContext;

/**
 * UC-001 clinic-scoped authentication. Drives the
 * {@link ClinicScopedAuthenticationProvider} through the real
 * {@link AuthenticationManager}, exercising the
 * {@code (clinic_slug, username)} key and the four disclosure-safe failure
 * paths. The clinic slug travels in
 * {@link ClinicWebAuthenticationDetails}, mirroring the form-login POST.
 */
@SpringBootTest(classes = Application.class)
class ClinicScopedAuthenticationProviderIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private UUID clinicAId;
    private UUID clinicBId;
    private String uniqueSuffix;

    @BeforeEach
    void setup() throws Exception {
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicAId = TestFixtures.insertClinic(connection, "Clinic A " + uniqueSuffix, "clinic-a-" + uniqueSuffix);
            clinicBId = TestFixtures.insertClinic(connection, "Clinic B " + uniqueSuffix, "clinic-b-" + uniqueSuffix);
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void successfulAuthenticationWithCorrectClinicCode() throws Exception {
        String rawPassword = "correct-horse-battery-staple";
        String username = "validuser-" + uniqueSuffix;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            TestFixtures.insertUser(connection, clinicAId, username, passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicAId, lookupUserId(connection, clinicAId, username));
        }

        Authentication authentication = authenticationManager.authenticate(
                authenticate(clinicASlug(), username, rawPassword));

        assertThat(authentication.isAuthenticated()).isTrue();
        Object principal = authentication.getPrincipal();
        assertThat(principal).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) principal;
        assertThat(authenticatedUser.getClinicId()).isEqualTo(clinicAId);
        assertThat(authenticatedUser.getUsername()).isEqualTo(username);
    }

    @Test
    void sameUsernameInTwoClinicsAuthenticatesAgainstTheRightClinic() throws Exception {
        String rawPassword = "correct-horse-battery-staple";
        String sharedUsername = "shared-" + uniqueSuffix;
        UUID userAId;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            userAId = TestFixtures.insertUser(connection, clinicAId, sharedUsername,
                    passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicAId, userAId);
            TestFixtures.insertUser(connection, clinicBId, sharedUsername,
                    passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicBId, lookupUserId(connection, clinicBId, sharedUsername));
        }

        Authentication asA = authenticationManager.authenticate(
                authenticate(clinicASlug(), sharedUsername, rawPassword));
        Authentication asB = authenticationManager.authenticate(
                authenticate(clinicBSlug(), sharedUsername, rawPassword));

        assertThat(((AuthenticatedUser) asA.getPrincipal()).getClinicId()).isEqualTo(clinicAId);
        assertThat(((AuthenticatedUser) asB.getPrincipal()).getClinicId()).isEqualTo(clinicBId);
    }

    @Test
    void clinicCodeMatchIsCaseInsensitive() throws Exception {
        String rawPassword = "correct-horse-battery-staple";
        String username = "mixedcase-" + uniqueSuffix;
        insertUserInClinicA(username, rawPassword);

        String shoutedSlug = clinicASlug().toUpperCase(Locale.ROOT);
        Authentication authentication = authenticationManager.authenticate(
                authenticate(shoutedSlug, username, rawPassword));

        assertThat(((AuthenticatedUser) authentication.getPrincipal()).getClinicId()).isEqualTo(clinicAId);
    }

    @Test
    void authenticationFailsWithWrongClinicCode() throws Exception {
        String rawPassword = "the-real-password";
        String username = "wrongclinic-" + uniqueSuffix;
        insertUserInClinicA(username, rawPassword);

        assertThatThrownBy(() -> authenticationManager.authenticate(
                authenticate(clinicBSlug(), username, rawPassword)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void authenticationFailsWithUnknownClinicCode() throws Exception {
        String rawPassword = "the-real-password";
        String username = "unknownclinic-" + uniqueSuffix;
        insertUserInClinicA(username, rawPassword);

        assertThatThrownBy(() -> authenticationManager.authenticate(
                authenticate("no-such-clinic-" + uniqueSuffix, username, rawPassword)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void authenticationFailsWithUnknownUsername() {
        assertThatThrownBy(() -> authenticationManager.authenticate(
                authenticate(clinicASlug(), "nonexistent-" + uniqueSuffix, "irrelevant")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void authenticationFailsWithNoMembershipsAtAll() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            TestFixtures.insertUser(connection, clinicAId, "nomember-" + uniqueSuffix,
                    passwordEncoder.encode("irrelevant"), "active");
        }

        assertThatThrownBy(() -> authenticationManager.authenticate(
                authenticate(clinicASlug(), "nomember-" + uniqueSuffix, "irrelevant")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void authenticationFailsWithWrongPassword() throws Exception {
        String username = "wrongpass-" + uniqueSuffix;
        insertUserInClinicA(username, "the-real-password");

        assertThatThrownBy(() -> authenticationManager.authenticate(
                authenticate(clinicASlug(), username, "not-the-real-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void authenticationFailsWithInactiveAccount() throws Exception {
        String username = "inactive-" + uniqueSuffix;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID userId = TestFixtures.insertUser(connection, clinicAId, username,
                    passwordEncoder.encode("whatever"), "suspended");
            TestFixtures.insertMembership(connection, clinicAId, userId);
        }

        assertThatThrownBy(() -> authenticationManager.authenticate(
                authenticate(clinicASlug(), username, "whatever")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    private void insertUserInClinicA(String username, String rawPassword) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID userId = TestFixtures.insertUser(connection, clinicAId, username,
                    passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicAId, userId);
        }
    }

    private UUID lookupUserId(Connection connection, UUID clinicId, String username) {
        return TestFixtures.lookupUserId(connection, clinicId, username);
    }

    private static UsernamePasswordAuthenticationToken authenticate(String slug, String username, String rawPassword) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("clinic", slug);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username, rawPassword);
        token.setDetails(new ClinicWebAuthenticationDetails(request));
        return token;
    }

    private String clinicASlug() {
        return "clinic-a-" + uniqueSuffix;
    }

    private String clinicBSlug() {
        return "clinic-b-" + uniqueSuffix;
    }
}