package com.clinicos.identity.internal;

import java.util.Locale;

import org.springframework.security.web.authentication.WebAuthenticationDetails;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Carries the clinic code entered on the login form alongside the standard
 * Spring authentication details. The clinic slug becomes part of the
 * authentication key {@code (clinic_slug, username)}. Normalized once here
 * (trimmed, lowercased) rather than at each call site, since {@code
 * clinic.slug} is always stored lowercase.
 */
public class ClinicWebAuthenticationDetails extends WebAuthenticationDetails {

    private final String clinicSlug;

    public ClinicWebAuthenticationDetails(HttpServletRequest request) {
        super(request);
        this.clinicSlug = normalize(request.getParameter("clinic"));
    }

    public String getClinicSlug() {
        return clinicSlug;
    }

    private static String normalize(String slug) {
        return slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
    }
}
