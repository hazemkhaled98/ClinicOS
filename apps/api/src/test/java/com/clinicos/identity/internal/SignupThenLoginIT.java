package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.SignupService;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.shared.TenantContext;

/**
 * The sign-up → login bridge (UC-001): a clinic owner who self-signs-up can
 * immediately authenticate through the clinic-scoped credential lookup with the
 * same raw password and the clinic code returned by the sign-up.
 */
@SpringBootTest(classes = Application.class)
class SignupThenLoginIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private SignupService signupService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void signedUpOwnerCanLoginThroughClinicScopedAuthentication() {
        String username = "signupthenlogin-" + uniqueSuffix();
        String rawPassword = "correct-horse-battery-staple";

        SignupService.SignupResult result = signupService.signUp(new SignupRequest(
                "Login Clinic " + uniqueSuffix(), "Dr Ahmed", username, null, rawPassword));
        assertThat(result.userId()).isNotNull();
        assertThat(result.clinicSlug()).isNotBlank();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("clinic", result.clinicSlug());
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username, rawPassword);
        token.setDetails(new ClinicWebAuthenticationDetails(request));

        Authentication authentication = authenticationManager.authenticate(token);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) authentication.getPrincipal();
        assertThat(authenticatedUser.getId()).isEqualTo(result.userId());
        assertThat(authenticatedUser.getClinicId()).isEqualTo(result.clinicId());
        assertThat(authenticatedUser.getUsername()).isEqualTo(username);
        assertThat(passwordEncoder.matches(rawPassword, authenticatedUser.getPassword())).isTrue();
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}