package com.clinicos.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class TemporaryPermitAllSecurityConfig {

    @Bean
    SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        http.csrf(csrf -> csrf.disable());
        return http.build();
    }
}
