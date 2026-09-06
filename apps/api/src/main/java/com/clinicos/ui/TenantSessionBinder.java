package com.clinicos.ui;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.identity.api.MembershipLookupService.Membership;
import com.clinicos.identity.api.PermissionsService;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;

@Component
public class TenantSessionBinder implements VaadinServiceInitListener {

    public static final String SESSION_CLINIC_ID = "clinicId";
    public static final String SESSION_MEMBERSHIP_ID = "membershipId";
    public static final String SESSION_ROLE_CODE = "roleCode";
    public static final String SESSION_PERMISSIONS = "permissions";

    private final MembershipLookupService membershipLookupService;
    private final PermissionsService permissionsService;
    private final ActivityLogService activityLogService;

    public TenantSessionBinder(MembershipLookupService membershipLookupService,
            PermissionsService permissionsService, ActivityLogService activityLogService) {
        this.membershipLookupService = membershipLookupService;
        this.permissionsService = permissionsService;
        this.activityLogService = activityLogService;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.addRequestHandler((session, request, response) -> {
            // VaadinSession.getAttribute asserts the session lock is held --
            // RequestHandler.handleRequest does not hold it by default.
            session.lock();
            try {
                primeSession(session);
                Object clinicId = session.getAttribute(SESSION_CLINIC_ID);
                if (clinicId instanceof UUID id) {
                    TenantContext.set(id);
                } else {
                    TenantContext.clear();
                }
            } finally {
                session.unlock();
            }
            return false;
        });
    }

    private void primeSession(VaadinSession session) {
        if (session.getAttribute(SESSION_CLINIC_ID) != null) {
            return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            return;
        }
        List<Membership> memberships;
        TenantContext.enterAuthMode();
        try {
            memberships = membershipLookupService.findByUserId(user.getId());
        } finally {
            TenantContext.exitAuthMode();
        }
        if (memberships.isEmpty()) {
            return;
        }
        Membership membership = memberships.getFirst();
        TenantContext.set(membership.clinicId());
        try {
            activityLogService.log(
                    membership.clinicId(),
                    membership.membershipId(),
                    "login",
                    "session");
            MembershipAccess access = permissionsService.accessFor(membership.membershipId());
            session.setAttribute(SESSION_CLINIC_ID, membership.clinicId());
            session.setAttribute(SESSION_MEMBERSHIP_ID, membership.membershipId());
            session.setAttribute(SESSION_ROLE_CODE, access.roleCode());
            session.setAttribute(SESSION_PERMISSIONS, List.copyOf(access.permissionCodes()));
        } finally {
            TenantContext.clear();
        }
    }
}
