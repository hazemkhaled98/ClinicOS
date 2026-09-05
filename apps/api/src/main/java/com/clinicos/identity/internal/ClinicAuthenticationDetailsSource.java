package com.clinicos.identity.internal;

import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Springs {@link AccountAuthenticationDetailsSource} behavior onto the clinic
 * code: builds a {@link ClinicWebAuthenticationDetails} per form-login request
 * so the authenticating provider can read the third login field.
 */
@Component
public class ClinicAuthenticationDetailsSource
        implements AuthenticationDetailsSource<HttpServletRequest, ClinicWebAuthenticationDetails> {

    @Override
    public ClinicWebAuthenticationDetails buildDetails(HttpServletRequest context) {
        return new ClinicWebAuthenticationDetails(context);
    }
}
