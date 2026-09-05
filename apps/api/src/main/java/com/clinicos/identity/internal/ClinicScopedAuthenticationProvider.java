package com.clinicos.identity.internal;

import org.springframework.security.authentication.AuthenticationProvider;
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
 * the {@link ClinicWebAuthenticationDetails}. All four failure paths (unknown
 * clinic/user, non-{@code active} status, no active membership, password
 * mismatch) throw the same generic
 * {@link BadCredentialsException("Invalid credentials")} so nothing is
 * disclosed about which condition failed.
 */
@Component
public class ClinicScopedAuthenticationProvider implements AuthenticationProvider {

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
        String rawPassword = (String) authentication.getCredentials();
        String clinicSlug = clinicSlug(authentication);

        TenantContext.enterAuthMode();
        try {
            CredentialsLookupService.CredentialsRow credentials = credentialsLookupService
                    .credentialsLookupByClinicAndUsername(normalize(clinicSlug), username);
            if (credentials == null) {
                throw new BadCredentialsException(GENERIC_ERROR);
            }

            if (!"active".equals(credentials.status())) {
                throw new BadCredentialsException(GENERIC_ERROR);
            }

            if (!credentialsLookupService.hasActiveMembership(credentials.id())) {
                throw new BadCredentialsException(GENERIC_ERROR);
            }

            if (!passwordEncoder.matches(rawPassword, credentials.passwordHash())) {
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

    private static String clinicSlug(Authentication authentication) {
        if (authentication.getDetails() instanceof ClinicWebAuthenticationDetails details) {
            return details.getClinicSlug();
        }
        return null;
    }

    private static String normalize(String slug) {
        return slug == null ? "" : slug.trim();
    }
}
