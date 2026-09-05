package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._click;
import static com.github.mvysny.kaributesting.v10.LocatorJ._find;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.clinicos.identity.api.AuthenticatedUser;
import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.identity.api.MembershipLookupService.Membership;
import com.clinicos.identity.api.PermissionsService;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.shared.ActivityLogService;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.server.VaadinSession;

@ExtendWith(MockitoExtension.class)
class ClinicPickerViewTest {

    private static Routes routes;

    @Mock
    private MembershipLookupService membershipLookupService;

    @Mock
    private PermissionsService permissionsService;

    @Mock
    private ActivityLogService activityLogService;

    private AuthenticatedUser testUser;

    @BeforeAll
    static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.clinicos");
    }

    @BeforeEach
    void setup() {
        MockVaadin.setup(routes);
        testUser = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "testuser", "hash");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(testUser, null, List.of()));
    }

    @AfterEach
    void teardown() {
        MockVaadin.tearDown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void showsClinicSelectionWhenMultipleMemberships() {
        UUID userId = testUser.getId();
        when(membershipLookupService.findByUserId(userId)).thenReturn(List.of(
                new Membership(UUID.randomUUID(), UUID.randomUUID(), "عيادة الأمل", "owner"),
                new Membership(UUID.randomUUID(), UUID.randomUUID(), "عيادة الشفاء", "manager")));

        ClinicPickerView view = new ClinicPickerView(membershipLookupService, permissionsService, activityLogService);

        H2 heading = _get(view, H2.class);
        assertThat(heading.getText()).isEqualTo("اختر العيادة");

        List<Button> buttons = _find(view, Button.class);
        assertThat(buttons).hasSize(2);
    }

    @Test
    void showsEmptyMessageWhenNoMemberships() {
        when(membershipLookupService.findByUserId(testUser.getId())).thenReturn(List.of());

        ClinicPickerView view = new ClinicPickerView(membershipLookupService, permissionsService, activityLogService);

        H2 heading = _get(view, H2.class);
        assertThat(heading.getText()).isEqualTo("لا توجد عيادات مسجلة");
    }

    @Test
    void selectingClinicSetsSessionAndLogsActivity() {
        UUID membershipId = UUID.randomUUID();
        UUID clinicId = UUID.randomUUID();
        when(membershipLookupService.findByUserId(testUser.getId())).thenReturn(List.of(
                new Membership(membershipId, clinicId, "عيادة الأمل", "owner")));
        when(permissionsService.accessFor(membershipId)).thenReturn(
                new MembershipAccess(membershipId, "owner", Set.of("emp", "quick", "ceo")));

        new ClinicPickerView(membershipLookupService, permissionsService, activityLogService);

        VaadinSession session = VaadinSession.getCurrent();
        assertThat(session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID)).isEqualTo(clinicId);
        assertThat(session.getAttribute(ClinicPickerView.SESSION_MEMBERSHIP_ID)).isEqualTo(membershipId);
        assertThat(session.getAttribute(ClinicPickerView.SESSION_ROLE_CODE)).isEqualTo("owner");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) session.getAttribute(ClinicPickerView.SESSION_PERMISSIONS);
        assertThat(permissions).containsExactlyInAnyOrder("emp", "quick", "ceo");

        verify(activityLogService).log(clinicId, membershipId, "login", "session");
        verify(permissionsService).accessFor(membershipId);
    }

    @Test
    void selectingClinicFromMultipleRedirectsAndLogsActivity() {
        UUID membershipId = UUID.randomUUID();
        UUID clinicId = UUID.randomUUID();
        when(membershipLookupService.findByUserId(testUser.getId())).thenReturn(List.of(
                new Membership(UUID.randomUUID(), UUID.randomUUID(), "عيادة الأمل", "owner"),
                new Membership(membershipId, clinicId, "عيادة الشفاء", "manager")));
        when(permissionsService.accessFor(membershipId)).thenReturn(
                new MembershipAccess(membershipId, "manager", Set.of("emp", "quick")));

        ClinicPickerView view = new ClinicPickerView(membershipLookupService, permissionsService, activityLogService);

        Button button = _get(view, Button.class, spec -> spec.withText("عيادة الشفاء"));
        _click(button);

        VaadinSession session = VaadinSession.getCurrent();
        assertThat(session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID)).isEqualTo(clinicId);
        assertThat(session.getAttribute(ClinicPickerView.SESSION_MEMBERSHIP_ID)).isEqualTo(membershipId);
        assertThat(session.getAttribute(ClinicPickerView.SESSION_ROLE_CODE)).isEqualTo("manager");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) session.getAttribute(ClinicPickerView.SESSION_PERMISSIONS);
        assertThat(permissions).containsExactlyInAnyOrder("emp", "quick");

        verify(activityLogService).log(clinicId, membershipId, "login", "session");
    }
}
