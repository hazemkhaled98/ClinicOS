package com.clinicos.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 0 placeholder only: permits every request so the smoke-test route is
 * reachable before real authentication exists. Deleted in Phase 1, which
 * replaces it with the actual login flow (UC-001) in the {@code identity}
 * module. Not a security posture — boot scaffolding.
 */
@Configuration
public class TemporaryPermitAllSecurityConfig {

    @Bean
    SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        http.csrf(csrf -> csrf.disable());
        return http.build();
    }
}
