package com.clinicos.identity.internal;

import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Builds a {@link ClinicWebAuthenticationDetails} per form-login request so
 * the authenticating provider can read the third login field (the clinic
 * code).
 */
@Component
public class ClinicAuthenticationDetailsSource
        implements AuthenticationDetailsSource<HttpServletRequest, ClinicWebAuthenticationDetails> {

    @Override
    public ClinicWebAuthenticationDetails buildDetails(HttpServletRequest context) {
        return new ClinicWebAuthenticationDetails(context);
    }
}
