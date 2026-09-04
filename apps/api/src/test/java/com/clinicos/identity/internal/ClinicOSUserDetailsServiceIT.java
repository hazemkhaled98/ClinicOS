package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class ClinicOSUserDetailsServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private ClinicOSUserDetailsService userDetailsService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private UUID testClinicId;
    private String uniqueSuffix;

    @BeforeEach
    void setup() throws Exception {
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            testClinicId = TestFixtures.insertClinic(connection, "Test Clinic " + uniqueSuffix, "test-clinic-" + uniqueSuffix);
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void successfulAuthenticationWithCorrectCredentials() throws Exception {
        String rawPassword = "correct-horse-battery-staple";
        String passwordHash = passwordEncoder.encode(rawPassword);
        UUID userId;

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            userId = TestFixtures.insertUser(connection, "validuser-" + uniqueSuffix, passwordHash, "active");
            TestFixtures.insertMembership(connection, testClinicId, userId);
        }

        UserDetails user = userDetailsService.loadUserByUsername("validuser-" + uniqueSuffix);

        assertThat(user).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) user;
        assertThat(authenticatedUser.getId()).isEqualTo(userId);
        assertThat(authenticatedUser.getUsername()).isEqualTo("validuser-" + uniqueSuffix);
        assertThat(authenticatedUser.getPassword()).isEqualTo(passwordHash);

        // End-to-end through the real AuthenticationManager, not just the
        // lookup -- proves Argon2 verification actually matches the raw
        // password against the stored hash, which loadUserByUsername alone
        // never exercises.
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken("validuser-" + uniqueSuffix, rawPassword));
        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    void authenticationFailsWithUnknownUsername() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nonexistent-" + uniqueSuffix))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("nonexistent");
    }

    @Test
    void authenticationFailsWithNoMembershipsAtAll() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            TestFixtures.insertUser(connection, "nomember-" + uniqueSuffix, passwordEncoder.encode("irrelevant"), "active");
        }

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nomember-" + uniqueSuffix))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("nomember");
    }

    @Test
    void authenticationFailsWithWrongPassword() throws Exception {
        String correctHash = passwordEncoder.encode("the-real-password");
        UUID userId;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            userId = TestFixtures.insertUser(connection, "wrongpass-" + uniqueSuffix, correctHash, "active");
            TestFixtures.insertMembership(connection, testClinicId, userId);
        }

        assertThatThrownBy(() -> authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken("wrongpass-" + uniqueSuffix, "not-the-real-password")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticationFailsWithInactiveAccount() throws Exception {
        UUID userId;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            userId = TestFixtures.insertUser(connection, "inactive-" + uniqueSuffix, passwordEncoder.encode("whatever"), "suspended");
            TestFixtures.insertMembership(connection, testClinicId, userId);
        }

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("inactive-" + uniqueSuffix))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("inactive");
    }



}
