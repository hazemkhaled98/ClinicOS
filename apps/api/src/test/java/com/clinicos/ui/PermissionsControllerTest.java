package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.identity.api.RolePermissionService;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class PermissionsControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private LayoutModel layoutModel;
    private RolePermissionService rolePermissionService;
    private UserAdminService userAdminService;
    private ActivityLogService activityLogService;
    private PermissionsController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        rolePermissionService = mock(RolePermissionService.class);
        userAdminService = mock(UserAdminService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new PermissionsController(layoutModel, rolePermissionService, userAdminService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void permissionsRendersPageForAdmin() {
        HttpSession session = session();
        allowDashboard();
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of());
        when(userAdminService.list(CLINIC)).thenReturn(List.of());

        String view = controller.permissions(session, model);

        assertThat(view).isEqualTo("admin/permissions-page");
        assertThat(model.getAttribute("rolePermissionMap")).isNotNull();
        assertThat(model.getAttribute("usersByRole")).isEqualTo(Map.of());
    }

    @Test
    void permissionsRedirectsHomeWithoutPermission() {
        HttpSession session = session();
        denyDashboard();

        String view = controller.permissions(session, model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void updatePermissionsLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of());
        when(userAdminService.list(CLINIC)).thenReturn(List.of());

        controller.updatePermissions("manager", new String[]{"emp", "quick"}, session, model);

        verify(rolePermissionService).setPermissions(CLINIC, "manager", Set.of("emp", "quick"));
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "permissions.update", "role_permission");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat(model.getAttribute("usersByRole")).isEqualTo(Map.of());
    }

    @Test
    void updatePermissionsHandlesNullCodes() {
        HttpSession session = session();
        allowDashboard();
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of());
        when(userAdminService.list(CLINIC)).thenReturn(List.of());

        controller.updatePermissions("assistant", null, session, model);

        verify(rolePermissionService).setPermissions(CLINIC, "assistant", Set.of());
    }

    @Test
    void usersByRoleGroupsUsersByRoleCode() {
        HttpSession session = session();
        allowDashboard();
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of());
        UserSummary assistant = new UserSummary(UUID.randomUUID(), "ehab", "إيهاب", "e@b.com", "active", "assistant", UUID.randomUUID(), null);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(assistant));

        controller.permissions(session, model);

        var usersByRole = (Map<String, List<UserSummary>>) model.getAttribute("usersByRole");
        assertThat(usersByRole.get("assistant")).containsExactly(assistant);
    }

    private void allowDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(NavSectionResolver.sectionByRoute("admin-dashboard")),
                        "أحمد", "المالك", "19 مايو 2026", "admin-dashboard"));
    }

    private void denyDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "مدير", "19 مايو 2026", "admin-dashboard"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}
