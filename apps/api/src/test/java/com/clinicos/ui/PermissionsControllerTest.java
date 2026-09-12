package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.identity.api.RolePermissionService;
import com.clinicos.identity.api.RolePermissionService.RolePermissionRow;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.identity.api.UserAdminService;
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
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of(
                new RolePermissionRow("manager", "emp"),
                new RolePermissionRow("manager", "quick")));

        String view = controller.permissions(session, model);

        assertThat(view).isEqualTo("admin/permissions-page");
        assertThat(model.getAttribute("rolePermissionMap")).isNotNull();
        assertThat((java.util.Map<String, ?>) model.getAttribute("rolePermissionMap")).containsKey("manager");
        assertThat(model.getAttribute("toastMessage")).isNull();
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
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of());

        String view = controller.updatePermissions(
                PermissionsController.PermissionsForm.of("manager", "emp", "quick"), Validated.of(PermissionsController.PermissionsForm.of("manager", "emp", "quick")), session, model);

        assertThat(view).isEqualTo("admin/permissions :: permissionsCard");
        verify(rolePermissionService).setPermissions(CLINIC, "manager",
                java.util.Set.of("emp", "quick"));
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "permissions.update", "role_permission");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ الصلاحيات");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void updatePermissionsBlankRoleCodeIsRejected() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of());

        String view = controller.updatePermissions(
                PermissionsController.PermissionsForm.of(" ", "emp"), Validated.of(PermissionsController.PermissionsForm.of(" ", "emp")), session, model);

        assertThat(view).isEqualTo("admin/permissions :: permissionsCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("الدور مطلوب");
        verify(rolePermissionService, never()).setPermissions(any(), any(), any());
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void updatePermissionsServiceErrorReturnsToast() {
        HttpSession session = session();
        allowDashboard();
        doThrow(new IllegalArgumentException("الدور المحدد غير موجود"))
                .when(rolePermissionService).setPermissions(eq(CLINIC), eq("manager"), any());
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(rolePermissionService.listForClinic(CLINIC)).thenReturn(List.of());

        String view = controller.updatePermissions(
                PermissionsController.PermissionsForm.of("manager", "emp"), Validated.of(PermissionsController.PermissionsForm.of("manager", "emp")), session, model);

        assertThat(view).isEqualTo("admin/permissions :: permissionsCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("الدور المحدد غير موجود");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void updatePermissionsListFailureRendersEmptyMap() {
        HttpSession session = session();
        allowDashboard();
        when(rolePermissionService.listForClinic(CLINIC)).thenThrow(new RuntimeException("boom"));
        when(userAdminService.list(CLINIC)).thenReturn(List.of());

        String view = controller.updatePermissions(
                PermissionsController.PermissionsForm.of("manager"), Validated.of(PermissionsController.PermissionsForm.of("manager")), session, model);

        assertThat(view).isEqualTo("admin/permissions :: permissionsCard");
        assertThat((java.util.Map<?, ?>) model.getAttribute("rolePermissionMap")).isEmpty();
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