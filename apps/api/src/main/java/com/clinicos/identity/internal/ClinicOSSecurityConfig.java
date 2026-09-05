package com.clinicos.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

/**
 * Spring Security configuration for ClinicOS. Delegates all Vaadin-specific
 * wiring (internal endpoint permit rules, CSRF, logout, the login route) to
 * {@link VaadinSecurityConfigurer} — Vaadin 25 dropped the older
 * {@code VaadinWebSecurity} base-class approach for this DSL-style
 * {@code AbstractHttpConfigurer}. Hand-rolling the permit-matcher list or
 * disabling CSRF here would be both redundant and less correct: Vaadin's
 * configurer already knows every internal endpoint it needs permitted, and
 * still protects the login POST with CSRF.
 */
@Configuration
@EnableWebSecurity
public class ClinicOSSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> configurer
                .loginView("/login", "/login")
                .addLogoutHandler(new CookieClearingLogoutHandler("lastSection")));
        return http.build();
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
