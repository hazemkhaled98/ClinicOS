package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.identity.api.MembershipLookupService.Membership;
import com.clinicos.identity.api.PermissionsService;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;

import jakarta.servlet.FilterChain;

class TenantSessionFilterTest {

    private MembershipLookupService membershipLookupService;
    private PermissionsService permissionsService;
    private ActivityLogService activityLogService;
    private TenantSessionFilter filter;

    @BeforeEach
    void setUp() {
        membershipLookupService = mock(MembershipLookupService.class);
        permissionsService = mock(PermissionsService.class);
        activityLogService = mock(ActivityLogService.class);
        filter = new TenantSessionFilter(membershipLookupService, permissionsService, activityLogService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void primesSessionFromAuthenticatedPrincipalAndLogsLogin() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID clinicId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        authenticate(userId);
        when(membershipLookupService.findByUserId(userId))
                .thenReturn(List.of(new Membership(membershipId, clinicId, "Clinic", "owner")));
        when(permissionsService.accessFor(membershipId))
                .thenReturn(new MembershipAccess(membershipId, "owner", Set.of("emp", "prep")));

        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.getAttribute(SessionKeys.CLINIC_ID)).isEqualTo(clinicId);
        assertThat(session.getAttribute(SessionKeys.CLINIC_NAME)).isEqualTo("Clinic");
        assertThat(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).isEqualTo(membershipId);
        assertThat(session.getAttribute(SessionKeys.ROLE_CODE)).isEqualTo("owner");
        assertThat(session.getAttribute(SessionKeys.PERMISSIONS))
                .isEqualTo(List.copyOf(Set.of("emp", "prep")));
        verify(activityLogService).log(eq(clinicId), eq(membershipId), eq("login"), eq("session"));
    }

    @Test
    void unauthenticatedRequestLeavesSessionUnprimed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getSession().getAttribute(SessionKeys.CLINIC_ID)).isNull();
        verify(membershipLookupService, never()).findByUserId(any(UUID.class));
        verify(activityLogService, never())
                .log(any(UUID.class), any(UUID.class), any(String.class), any(String.class));
    }

    @Test
    void authenticatedUserWithoutMembershipsLeavesSessionUnprimed() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        when(membershipLookupService.findByUserId(userId)).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getSession().getAttribute(SessionKeys.CLINIC_ID)).isNull();
        verify(activityLogService, never())
                .log(any(UUID.class), any(UUID.class), any(String.class), any(String.class));
    }

    @Test
    void prePrimedSessionSkipsRePrimeButStillBindsTenantContext() throws Exception {
        UUID clinicId = UUID.randomUUID();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.CLINIC_ID, clinicId);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        FilterChain recordingChain = (req, res) -> {
            assertThat(TenantContext.get()).isEqualTo(Optional.of(clinicId));
        };
        filter.doFilter(request, new MockHttpServletResponse(), recordingChain);

        verify(membershipLookupService, never()).findByUserId(any(UUID.class));
    }

    @Test
    void multiMembershipPrincipalPrimesTheMembershipOfTheAuthenticatedClinic() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID clinicA = UUID.randomUUID();
        UUID clinicB = UUID.randomUUID();
        UUID membershipA = UUID.randomUUID();
        UUID membershipB = UUID.randomUUID();
        authenticate(userId, clinicB);
        when(membershipLookupService.findByUserId(userId))
                .thenReturn(List.of(new Membership(membershipA, clinicA, "Clinic A", "owner"),
                        new Membership(membershipB, clinicB, "Clinic B", "owner")));
        when(permissionsService.accessFor(membershipB))
                .thenReturn(new MembershipAccess(membershipB, "owner", Set.of("emp")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpSession session = (MockHttpSession) request.getSession();
        assertThat(session.getAttribute(SessionKeys.CLINIC_ID)).isEqualTo(clinicB);
        assertThat(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).isEqualTo(membershipB);
    }

    @Test
    void primingFailureDoesNotBurnOneShotGuarantee() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID clinicId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        authenticate(userId);
        when(membershipLookupService.findByUserId(userId))
                .thenReturn(List.of(new Membership(membershipId, clinicId, "Clinic", "owner")));
        when(permissionsService.accessFor(membershipId))
                .thenThrow(new IllegalArgumentException("boom"))
                .thenReturn(new MembershipAccess(membershipId, "owner", Set.of("emp")));

        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.getAttribute(SessionKeys.CLINIC_ID)).isNull();
        assertThat(session.getAttribute(SessionKeys.PRIMING_ATTEMPTED)).isNull();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.getAttribute(SessionKeys.CLINIC_ID)).isEqualTo(clinicId);
        assertThat(session.getAttribute(SessionKeys.ROLE_CODE)).isEqualTo("owner");
    }

    private static void authenticate(UUID userId) {
        authenticate(userId, UUID.randomUUID());
    }

    private static void authenticate(UUID userId, UUID clinicId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, clinicId, "owner1", "hash");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
