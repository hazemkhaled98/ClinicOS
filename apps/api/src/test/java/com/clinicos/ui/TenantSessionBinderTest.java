package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.identity.api.MembershipLookupService.Membership;
import com.clinicos.identity.api.PermissionsService;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

class TenantSessionBinderTest {

    private final MembershipLookupService membershipLookupService = mock(MembershipLookupService.class);
    private final PermissionsService permissionsService = mock(PermissionsService.class);
    private final ActivityLogService activityLogService = mock(ActivityLogService.class);

    private final TenantSessionBinder binder = new TenantSessionBinder(
            membershipLookupService, permissionsService, activityLogService);

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void bindsTenantContextFromSelectedClinicInSession() throws Exception {
        UUID clinicId = UUID.randomUUID();
        RequestHandler handler = registeredHandler();
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(TenantSessionBinder.SESSION_CLINIC_ID)).thenReturn(clinicId);

        handler.handleRequest(session, mock(VaadinRequest.class), mock(VaadinResponse.class));

        assertThat(TenantContext.get()).contains(clinicId);
    }

    @Test
    void clearsTenantContextWhenNoClinicSelected() throws Exception {
        TenantContext.set(UUID.randomUUID());
        RequestHandler handler = registeredHandler();
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(TenantSessionBinder.SESSION_CLINIC_ID)).thenReturn(null);

        handler.handleRequest(session, mock(VaadinRequest.class), mock(VaadinResponse.class));

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void primesSessionFromAuthenticatedPrincipal() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID clinicId = UUID.randomUUID();
        securityContext(userId);
        when(membershipLookupService.findByUserId(userId)).thenReturn(List.of(
                new Membership(membershipId, clinicId, "عيادة الأمل", "owner")));
        when(permissionsService.accessFor(membershipId)).thenReturn(
                new MembershipAccess(membershipId, "owner", Set.of("emp", "quick", "ceo")));

        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(TenantSessionBinder.SESSION_CLINIC_ID)).thenReturn(null, clinicId);

        registeredHandler().handleRequest(session, mock(VaadinRequest.class), mock(VaadinResponse.class));

        verify(session).setAttribute(TenantSessionBinder.SESSION_CLINIC_ID, clinicId);
        verify(session).setAttribute(TenantSessionBinder.SESSION_MEMBERSHIP_ID, membershipId);
        verify(session).setAttribute(TenantSessionBinder.SESSION_ROLE_CODE, "owner");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> permissions = ArgumentCaptor.forClass(List.class);
        verify(session).setAttribute(eq(TenantSessionBinder.SESSION_PERMISSIONS), permissions.capture());
        assertThat(permissions.getValue()).containsExactlyInAnyOrder("emp", "quick", "ceo");
        verify(activityLogService).log(clinicId, membershipId, "login", "session");
        assertThat(TenantContext.get()).contains(clinicId);
    }

    @Test
    void doesNotReLookupWhenClinicAlreadyInSession() throws Exception {
        UUID clinicId = UUID.randomUUID();
        securityContext(UUID.randomUUID());

        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(TenantSessionBinder.SESSION_CLINIC_ID)).thenReturn(clinicId);

        registeredHandler().handleRequest(session, mock(VaadinRequest.class), mock(VaadinResponse.class));

        verify(membershipLookupService, never()).findByUserId(any());
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void skipsPrimingWhenUnauthenticated() throws Exception {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(TenantSessionBinder.SESSION_CLINIC_ID)).thenReturn(null);

        registeredHandler().handleRequest(session, mock(VaadinRequest.class), mock(VaadinResponse.class));

        verify(membershipLookupService, never()).findByUserId(any());
        assertThat(TenantContext.get()).isEmpty();
    }

    private void securityContext(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(userId, UUID.randomUUID(), "testuser", "hash"),
                        null, List.of()));
    }

    private RequestHandler registeredHandler() {
        ServiceInitEvent event = new ServiceInitEvent(mock(VaadinService.class));
        binder.serviceInit(event);
        return event.getAddedRequestHandlers().findFirst().orElseThrow();
    }
}