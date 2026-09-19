package com.clinicos.ui;

import java.util.Collection;
import java.util.UUID;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

/**
 * The admin dashboard (ceo) check shared by every controller that serves
 * {@code /admin-dashboard/**}: the requesting session must see the
 * admin-dashboard section in its nav, i.e. the owner (or a delegated
 * administrator) is logged in.
 */
final class AdminAccess {

    private AdminAccess() {
    }

    static boolean canDashboard(LayoutModel layoutModel, HttpSession session) {
        return layoutModel.forRequest(session, "admin-dashboard")
                .nav()
                .contains(NavSectionResolver.sectionByRoute("admin-dashboard"));
    }

    static UUID clinicId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.CLINIC_ID);
    }

    static UUID membershipId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID);
    }

    static String roleCode(HttpSession session) {
        return (String) session.getAttribute(SessionKeys.ROLE_CODE);
    }

    static boolean hasCode(HttpSession session, String code) {
        Object permissions = session.getAttribute(SessionKeys.PERMISSIONS);
        return permissions instanceof Collection<?> codes && codes.contains(code);
    }
}