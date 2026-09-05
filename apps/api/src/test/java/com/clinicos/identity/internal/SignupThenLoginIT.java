package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.SignupService;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.shared.TenantContext;

/**
 * The sign-up → login bridge (UC-001): a clinic owner who self-signs-up can
 * immediately authenticate through the normal credential lookup with the same
 * raw password.
 */
@SpringBootTest(classes = Application.class)
class SignupThenLoginIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private SignupService signupService;

    @Autowired
    private ClinicOSUserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void signedUpOwnerCanLoginThroughCredentialsLookup() {
        String username = "signupthenlogin-" + uniqueSuffix();
        String rawPassword = "correct-horse-battery-staple";

        SignupService.SignupResult result = signupService.signUp(new SignupRequest(
                "Login Clinic " + uniqueSuffix(), "Dr Ahmed", username, null, rawPassword));
        assertThat(result.userId()).isNotNull();

        UserDetails user = userDetailsService.loadUserByUsername(username);

        assertThat(user).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) user;
        assertThat(authenticatedUser.getId()).isEqualTo(result.userId());
        assertThat(authenticatedUser.getUsername()).isEqualTo(username);
        assertThat(passwordEncoder.matches(rawPassword, authenticatedUser.getPassword())).isTrue();
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}