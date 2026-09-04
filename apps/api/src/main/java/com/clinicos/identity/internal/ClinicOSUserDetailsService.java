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
 * <p>All failure paths (unknown user, wrong password, inactive account, no
 * active membership) return the same generic exception, never disclosing which
 * condition failed — this prevents username enumeration and other information
 * leakage attacks.
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
