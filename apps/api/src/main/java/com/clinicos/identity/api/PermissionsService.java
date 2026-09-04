package com.clinicos.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves what a clinic membership is allowed to see (BR-G02, BR-G03).
 * The effective permission set of a membership is its role's default grants
 * ({@code role_permission}) plus per-member grants ({@code membership_permission}
 * with {@code granted = true}), minus per-member revokes ({@code granted = false}).
 * A member whose role is {@code owner} is granted every permission regardless of
 * any other setting (BR-G03).
 */
public interface PermissionsService {

    MembershipAccess accessFor(UUID membershipId);

    record MembershipAccess(UUID membershipId, String roleCode, Set<String> permissionCodes) {
    }
}