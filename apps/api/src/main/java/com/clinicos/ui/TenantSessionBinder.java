package com.clinicos.ui;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Rebinds {@link TenantContext} from the session's selected clinic at the
 * start of every Vaadin request, and primes that selection from the
 * authenticated principal's single membership on first use per session.
 *
 * <p>Priming (one login activity-log row plus the session's
 * clinic/membership/role/permissions attributes) runs at most once per
 * session, guarded by a priming-attempted marker, so a failing dependency
 * does not retry -- and re-log -- on every subsequent request. An
 * unauthenticated principal or a user with no clinic memberships leaves the
 * session unprimed.
 *
 * <p>Registered as a {@link com.vaadin.flow.server.RequestHandler}
 * rather than a navigation listener so it also covers non-navigating
 * server round trips (e.g. an in-place server call on the current view),
 * not only page loads.
 */
@Component
public class TenantSessionBinder implements VaadinServiceInitListener {

    private static final Logger log = LoggerFactory.getLogger(TenantSessionBinder.class);

    public static final String SESSION_CLINIC_ID = "clinicId";
    public static final String SESSION_MEMBERSHIP_ID = "membershipId";
    public static final String SESSION_ROLE_CODE = "roleCode";
    public static final String SESSION_PERMISSIONS = "permissions";
    public static final String SESSION_PRIMING_ATTEMPTED = "primingAttempted";

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
        if (session.getAttribute(SESSION_PRIMING_ATTEMPTED) != null
                || session.getAttribute(SESSION_CLINIC_ID) != null) {
            return;
        }
        session.setAttribute(SESSION_PRIMING_ATTEMPTED, Boolean.TRUE);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            log.debug("No authenticated principal -- skipping clinic priming");
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
            log.debug("Authenticated user {} has zero clinic memberships -- nothing to prime",
                    user.getId());
            return;
        }
        Membership membership = memberships.getFirst();
        try {
            TenantContext.set(membership.clinicId());
            MembershipAccess access = permissionsService.accessFor(membership.membershipId());
            session.setAttribute(SESSION_CLINIC_ID, membership.clinicId());
            session.setAttribute(SESSION_MEMBERSHIP_ID, membership.membershipId());
            session.setAttribute(SESSION_ROLE_CODE, access.roleCode());
            session.setAttribute(SESSION_PERMISSIONS, List.copyOf(access.permissionCodes()));
            activityLogService.log(
                    membership.clinicId(),
                    membership.membershipId(),
                    "login",
                    "session");
        } catch (org.springframework.dao.DataAccessException
                | org.jooq.exception.DataAccessException
                | IllegalArgumentException e) {
            log.error("Failed to prime clinic session: clinic={} membership={} user={}",
                    membership.clinicId(), membership.membershipId(), user.getId(), e);
        } finally {
            TenantContext.clear();
        }
    }
}
