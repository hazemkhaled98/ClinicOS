package com.clinicos.identity.internal;

import org.springframework.security.web.authentication.WebAuthenticationDetails;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Carries the clinic code entered on the login form alongside the standard
 * Spring authentication details. The clinic slug becomes part of the
 * authentication key {@code (clinic_slug, username)}.
 */
public class ClinicWebAuthenticationDetails extends WebAuthenticationDetails {

    private final String clinicSlug;

    public ClinicWebAuthenticationDetails(HttpServletRequest request) {
        super(request);
        this.clinicSlug = request.getParameter("clinic");
    }

    public String getClinicSlug() {
        return clinicSlug;
    }
}
