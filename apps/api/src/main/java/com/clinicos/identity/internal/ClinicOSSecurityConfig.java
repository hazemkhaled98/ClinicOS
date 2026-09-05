package com.clinicos.identity.internal;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security configuration for ClinicOS. Delegates all Vaadin-specific
 * wiring (internal endpoint permit rules, CSRF, logout, the login route) to
 * {@link VaadinSecurityConfigurer} — Vaadin 25 dropped the older
 * {@code VaadinWebSecurity} base-class approach for this DSL-style
 * {@code AbstractHttpConfigurer}. Hand-rolling the permit-matcher list or
 * disabling CSRF here would be both redundant and less correct: Vaadin's
 * configurer already knows every internal endpoint it needs permitted, and
 * still protects the login POST with CSRF.
 *
 * <p>Login is clinic-scoped: the form-login filter uses a clinic-code
 * {@link ClinicAuthenticationDetailsSource} so the provider can see the third
 * form field, and authentication runs through
 * {@link ClinicScopedAuthenticationProvider}. The failure handler re-appends
 * the submitted clinic code to the {@code /login?error} redirect, since
 * Spring's default failure redirect otherwise drops it and the user would
 * have to retype it after every failed attempt.
 */
@Configuration
@EnableWebSecurity
public class ClinicOSSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ClinicAuthenticationDetailsSource clinicAuthenticationDetailsSource,
            ClinicScopedAuthenticationProvider clinicScopedAuthenticationProvider) throws Exception {
        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> configurer
                .loginView("/login", "/login")
                .addLogoutHandler(new CookieClearingLogoutHandler("lastSection")));
        http.formLogin(f -> f.authenticationDetailsSource(clinicAuthenticationDetailsSource)
                .failureHandler(ClinicOSSecurityConfig::redirectToLoginWithClinicPreserved));
        http.authenticationProvider(clinicScopedAuthenticationProvider);
        return http.build();
    }

    private static void redirectToLoginWithClinicPreserved(HttpServletRequest request,
            HttpServletResponse response, AuthenticationException exception) throws IOException {
        String clinic = request.getParameter("clinic");
        String target = "/login?error";
        if (clinic != null && !clinic.isBlank()) {
            target += "&clinic=" + URLEncoder.encode(clinic, StandardCharsets.UTF_8);
        }
        response.sendRedirect(request.getContextPath() + target);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
