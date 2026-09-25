package com.clinicos.identity.api;

/**
 * HttpSession attribute keys for the clinic-selection state written by the
 * session-priming filter and read by the UI layer. Kept on the identity API
 * boundary so {@code ui} can read the keys without reaching into identity
 * internals.
 */
public final class SessionKeys {

    public static final String CLINIC_ID = "clinicId";
    public static final String CLINIC_NAME = "clinicName";
    public static final String MEMBERSHIP_ID = "membershipId";
    public static final String ROLE_CODE = "roleCode";
    public static final String PERMISSIONS = "permissions";
    public static final String PRIMING_ATTEMPTED = "primingAttempted";

    private SessionKeys() {
    }
}