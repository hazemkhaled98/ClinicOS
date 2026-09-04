package com.clinicos.identity.internal;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.shared.TenantContext;

/**
 * Loads user credentials during Spring Security authentication. Uses auth mode
 * to access the database before a clinic is selected.
 *
 * <p>The failure paths this service can detect (unknown user, inactive
 * account, no active membership) all throw the same generic
 * {@link UsernameNotFoundException}, never disclosing which condition failed.
 * Password verification happens later, in {@code DaoAuthenticationProvider},
 * which throws a separate {@code BadCredentialsException} on mismatch — both
 * exception types map to the same generic error text at {@code LoginView}, so
 * nothing is disclosed to the user either way.
 */
@Service
public class ClinicOSUserDetailsService implements UserDetailsService {

    private final CredentialsLookupService credentialsLookupService;

    public ClinicOSUserDetailsService(CredentialsLookupService credentialsLookupService) {
        this.credentialsLookupService = credentialsLookupService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        TenantContext.enterAuthMode();
        try {
            CredentialsLookupService.CredentialsRow credentials = credentialsLookupService
                    .credentialsLookupByUsername(username);
            if (credentials == null) {
                throw new UsernameNotFoundException(genericErrorMessage());
            }

            if (!"active".equals(credentials.status())) {
                throw new UsernameNotFoundException(genericErrorMessage());
            }

            if (!credentialsLookupService.hasActiveMembership(credentials.id())) {
                throw new UsernameNotFoundException(genericErrorMessage());
            }

            return new AuthenticatedUser(credentials.id(), username, credentials.passwordHash());
        } finally {
            TenantContext.exitAuthMode();
        }
    }

    private static String genericErrorMessage() {
        return "Invalid credentials";
    }
}
