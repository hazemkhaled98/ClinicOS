package com.clinicos.ui;

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
}