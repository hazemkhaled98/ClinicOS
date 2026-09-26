package com.clinicos.identity.internal;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.identity.api.MembershipLookupService.Membership;
import com.clinicos.identity.api.PermissionsService;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Rebinds {@link TenantContext} from the session's selected clinic at the
 * start of every HTTP request, and primes that selection from the
 * membership that belongs to the clinic the principal authenticated against
 * on first use per session.
 *
 * <p>This is the direct replacement for Vaadin's
 * {@code TenantSessionBinder} (a {@code VaadinServiceInitListener}); it runs
 * once per HTTP request as a {@code OncePerRequestFilter}. SESSION_* attribute
 * keys are unchanged so existing session state survives the migration.
 *
 * <p>Priming (one login activity-log row plus the session's
 * clinic/membership/role/permissions attributes) runs at most once per
 * session, guarded by a priming-attempted marker, so a failing dependency
 * does not retry -- and re-log -- on every subsequent request. An
 * unauthenticated principal or a user with no clinic memberships leaves the
 * session unprimed.
 *
 * <p>Registered in {@link ClinicOSSecurityConfig} after Spring Security has
 * established the security context, so it can read the already-authenticated
 * principal from {@link SecurityContextHolder}. Being injected into the
 * security chain rather than auto-discovered as a {@code @Component} filter is
 * load-bearing: a plain servlet filter would run before the
 * {@code springSecurityFilterChain} and see an empty context on every request,
 * silently skipping clinic priming.
 */
@Component
public class TenantSessionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantSessionFilter.class);

    private final MembershipLookupService membershipLookupService;
    private final PermissionsService permissionsService;
    private final ActivityLogService activityLogService;

    public TenantSessionFilter(MembershipLookupService membershipLookupService,
            PermissionsService permissionsService, ActivityLogService activityLogService) {
        this.membershipLookupService = membershipLookupService;
        this.permissionsService = permissionsService;
        this.activityLogService = activityLogService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            apply(request);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void apply(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            TenantContext.clear();
            return;
        }
        primeSession(session);
        Object clinicId = session.getAttribute(SessionKeys.CLINIC_ID);
        if (clinicId instanceof UUID id) {
            TenantContext.set(id);
        } else {
            TenantContext.clear();
        }
    }

    private void primeSession(HttpSession session) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            if (session.getAttribute(SessionKeys.PRIMING_ATTEMPTED) != null
                    || session.getAttribute(SessionKeys.CLINIC_ID) != null) {
                return;
            }
            log.debug("No authenticated principal -- skipping clinic priming");
            return;
        }
        if (user.getId().equals(session.getAttribute(SessionKeys.PRIMED_USER_ID))
                && (session.getAttribute(SessionKeys.PRIMING_ATTEMPTED) != null
                        || session.getAttribute(SessionKeys.CLINIC_ID) != null)) {
            return;
        }
        // A second login inside the same browser session must not inherit the
        // previous user's membership, role, and permissions: drop whatever the
        // earlier principal primed before binding the new one.
        session.removeAttribute(SessionKeys.CLINIC_ID);
        session.removeAttribute(SessionKeys.CLINIC_NAME);
        session.removeAttribute(SessionKeys.MEMBERSHIP_ID);
        session.removeAttribute(SessionKeys.ROLE_CODE);
        session.removeAttribute(SessionKeys.PERMISSIONS);
        // Marker goes here, after the principal check: an unauthenticated
        // request must not burn the one-shot guarantee, or the first request
        // after login would find the marker set and never prime.
        session.setAttribute(SessionKeys.PRIMING_ATTEMPTED, Boolean.TRUE);
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
        Membership membership = memberships.stream()
                .filter(m -> m.clinicId().equals(user.getClinicId()))
                .findFirst()
                .orElse(memberships.getFirst());
        try {
            TenantContext.set(membership.clinicId());
            MembershipAccess access = permissionsService.accessFor(membership.membershipId());
            session.setAttribute(SessionKeys.CLINIC_ID, membership.clinicId());
            session.setAttribute(SessionKeys.CLINIC_NAME, membership.clinicName());
            session.setAttribute(SessionKeys.MEMBERSHIP_ID, membership.membershipId());
            session.setAttribute(SessionKeys.ROLE_CODE, access.roleCode());
            session.setAttribute(SessionKeys.PERMISSIONS, List.copyOf(access.permissionCodes()));
            session.setAttribute(SessionKeys.PRIMED_USER_ID, user.getId());
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
            session.removeAttribute(SessionKeys.PRIMING_ATTEMPTED);
        } finally {
            TenantContext.clear();
        }
    }
}