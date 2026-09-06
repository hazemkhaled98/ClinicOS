package com.clinicos.identity.internal;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.security.web.context.SecurityContextHolderFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security configuration for ClinicOS. The UI is server-rendered
 * Thymeleaf, so this wires plain form login against the MVC
 * {@code /login} page instead of a Vaadin view.
 *
 * <p>Login is clinic-scoped: the form-login filter uses a clinic-code
 * {@link ClinicAuthenticationDetailsSource} so the provider can see the third
 * form field, and authentication runs through
 * {@link ClinicScopedAuthenticationProvider}. The failure handler re-appends
 * the submitted clinic code to the {@code /login?error} redirect, since
 * Spring's default failure redirect otherwise drops it and the user would
 * have to retype it after every failed attempt.
 *
 * <p>Static assets (built Tailwind CSS, self-hosted fonts, the brand logo)
 * are permitted; everything else requires authentication. CSRF is left
 * enabled (the default); the login page includes the token as a Thymeleaf
 * hidden field and the app shell forwards it to HTMX via {@code hx-headers}.
 */
@Configuration
@EnableWebSecurity
public class ClinicOSSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ClinicAuthenticationDetailsSource clinicAuthenticationDetailsSource,
            ClinicScopedAuthenticationProvider clinicScopedAuthenticationProvider,
            TenantSessionFilter tenantSessionFilter) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/signup", "/error",
                        "/css/**", "/js/**", "/fonts/**", "/branding/**")
                .permitAll()
                .anyRequest().authenticated());
        http.formLogin(f -> f.loginPage("/login")
                .defaultSuccessUrl("/", true)
                .authenticationDetailsSource(clinicAuthenticationDetailsSource)
                .failureHandler(ClinicOSSecurityConfig::redirectToLoginWithClinicPreserved));
        http.logout(l -> l.logoutUrl("/logout")
                .addLogoutHandler(new CookieClearingLogoutHandler("lastSection")));
        http.authenticationProvider(clinicScopedAuthenticationProvider);
        http.addFilterAfter(tenantSessionFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    /**
     * Runs tenant binding/clinic priming inside the security chain (after the
     * security context is loaded) rather than as an auto-discovered servlet
     * filter. The companion {@link FilterRegistrationBean} disables that auto
     * registration so the filter does not also run before Spring Security and
     * see an empty security context on every request.
     */
    @Bean
    FilterRegistrationBean<TenantSessionFilter> tenantSessionFilterRegistration(
            TenantSessionFilter tenantSessionFilter) {
        FilterRegistrationBean<TenantSessionFilter> registration = new FilterRegistrationBean<>(tenantSessionFilter);
        registration.setEnabled(false);
        return registration;
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
