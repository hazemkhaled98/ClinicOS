package com.clinicos.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.shared.TenantContext;

/**
 * Authenticates a user against a single clinic (UC-001). Replaces the
 * {@code DaoAuthenticationProvider} + {@code ClinicOSUserDetailsService}
 * pairing because a {@code UserDetailsService} cannot see the clinic code.
 *
 * <p>The authentication key is {@code (clinic_slug, username)} gathered from
 * the {@link ClinicWebAuthenticationDetails}, which normalizes the slug.
 * All four failure paths (unknown clinic/user, non-{@code active} status, no
 * active membership, password mismatch) throw the same generic
 * {@code BadCredentialsException} with message {@link #GENERIC_ERROR} so
 * nothing is disclosed to the user about which condition failed; each is
 * still logged internally at DEBUG (clinic slug + username, never the
 * password) so a brute-force or misconfiguration pattern is still visible in
 * logs.
 */
@Component
public class ClinicScopedAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(ClinicScopedAuthenticationProvider.class);
    private static final String GENERIC_ERROR = "Invalid credentials";

    private final CredentialsLookupService credentialsLookupService;
    private final PasswordEncoder passwordEncoder;

    public ClinicScopedAuthenticationProvider(CredentialsLookupService credentialsLookupService,
            PasswordEncoder passwordEncoder) {
        this.credentialsLookupService = credentialsLookupService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        Object rawCredentials = authentication.getCredentials();
        if (!(rawCredentials instanceof String rawPassword)) {
            log.debug("auth failed: credentials were not a String (type={})",
                    rawCredentials == null ? "null" : rawCredentials.getClass());
            throw new BadCredentialsException(GENERIC_ERROR);
        }
        String clinicSlug = clinicSlug(authentication, username);

        TenantContext.enterAuthMode();
        try {
            CredentialsLookupService.CredentialsRow credentials;
            boolean hasActiveMembership;
            try {
                credentials = credentialsLookupService.credentialsLookupByClinicAndUsername(clinicSlug, username);
                hasActiveMembership = credentials != null && credentialsLookupService.hasActiveMembership(credentials.id());
            } catch (DataAccessException e) {
                log.error("auth infra failure looking up credentials for clinic={} username={}", clinicSlug, username, e);
                throw new AuthenticationServiceException("Authentication temporarily unavailable", e);
            }

            if (credentials == null) {
                log.debug("auth failed: unknown clinic/user clinic={} username={}", clinicSlug, username);
                throw new BadCredentialsException(GENERIC_ERROR);
            }

            if (!"active".equals(credentials.status())) {
                log.debug("auth failed: inactive account clinic={} username={}", clinicSlug, username);
                throw new BadCredentialsException(GENERIC_ERROR);
            }

            // Safe to check "any active membership" without re-scoping to clinicSlug:
            // V14 makes app_user.clinic_id NOT NULL and unique per user, so a user
            // can only ever hold a membership in their own single clinic.
            if (!hasActiveMembership) {
                log.debug("auth failed: no active membership clinic={} username={}", clinicSlug, username);
                throw new BadCredentialsException(GENERIC_ERROR);
            }

            if (!passwordEncoder.matches(rawPassword, credentials.passwordHash())) {
                log.debug("auth failed: wrong password clinic={} username={}", clinicSlug, username);
                throw new BadCredentialsException(GENERIC_ERROR);
            }

            AuthenticatedUser principal = new AuthenticatedUser(
                    credentials.id(), credentials.clinicId(), username, credentials.passwordHash());
            return UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities());
        } finally {
            TenantContext.exitAuthMode();
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static String clinicSlug(Authentication authentication, String username) {
        if (authentication.getDetails() instanceof ClinicWebAuthenticationDetails details) {
            return details.getClinicSlug();
        }
        log.warn("authentication details missing/wrong type for username={}: {}", username,
                authentication.getDetails() == null ? "null" : authentication.getDetails().getClass());
        return "";
    }
}
