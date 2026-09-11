package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserCreateRequest;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.identity.api.UserAdminService.UserValidationException;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.EmployeeService.StaffRole;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class UserAdminControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private LayoutModel layoutModel;
    private UserAdminService userAdminService;
    private EmployeeService employeeService;
    private ActivityLogService activityLogService;
    private TransactionTemplate transactionTemplate;
    private UserAdminController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        userAdminService = mock(UserAdminService.class);
        employeeService = mock(EmployeeService.class);
        activityLogService = mock(ActivityLogService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        controller = new UserAdminController(layoutModel, userAdminService, employeeService, activityLogService, transactionTemplate);
        doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        model = new ExtendedModelMap();
    }

    @Test
    void usersRendersPageForAdmin() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());

        String view = controller.users(session, model);

        assertThat(view).isEqualTo("admin/users-page");
        assertThat(model.getAttribute("users")).isEqualTo(List.of());
    }

    @Test
    void usersRedirectsHomeWithoutPermission() {
        HttpSession session = session();
        denyDashboard();

        String view = controller.users(session, model);

        assertThat(view).isEqualTo("redirect:/");
    }

@Test
    void createUserLogsActivityAndReturnsCard() {
        HttpSession session = session();
        allowDashboard();
        UserSummary created = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", "a@b.com", "active", "owner", UUID.randomUUID(), null);
        when(userAdminService.create(eq(CLINIC), any(UserCreateRequest.class))).thenReturn(created);
        when(employeeService.create(eq(CLINIC), any())).thenReturn(new Employee(UUID.randomUUID(), "أحمد", StaffRole.ASSISTANT,
                null, null, null, null, false, null, null));
        when(userAdminService.list(CLINIC)).thenReturn(List.of(created));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(
                UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123"), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.create", "user");
        assertThat(model.getAttribute("userErrorScope")).isNull();
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void createUserValidatesEmptyUsername() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(
                UserAdminController.UserForm.of("", "أحمد", "a@b.com", "hash123"), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(model.getAttribute("userErrors")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).containsKey("username")).isTrue();
        verify(userAdminService, never()).create(any(), any());
    }

    @Test
    void suspendLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.suspend(userId, session, model);

        verify(userAdminService).suspend(CLINIC, userId, MEMBERSHIP);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.suspend", "user");
    }

    @Test
    void suspendGuardViolationReportsErrorAndSkipsLog() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("يمكنك تعليق حسابك الخاص"))
                .when(userAdminService).suspend(CLINIC, userId, MEMBERSHIP);
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.suspend(userId, session, model);

        assertThat(model.getAttribute("userErrorScope")).isEqualTo("suspend");
        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).get("user"))
                .isEqualTo("يمكنك تعليق حسابك الخاص");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void reactivateLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.reactivate(userId, session, model);

        verify(userAdminService).reactivate(CLINIC, userId);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.reactivate", "user");
    }

    @Test
    void assignRoleLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        UUID membershipId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.assignRole(UUID.randomUUID(), "manager", membershipId, session, model);

        verify(userAdminService).assignRole(CLINIC, membershipId, "manager");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.assign_role", "user");
    }

    @Test
    void changePasswordBlankPasswordReportsError() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.changePassword(userId, "  ", session, model);

        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).containsKey("user")).isFalse();
        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).containsKey("password")).isTrue();
        verify(userAdminService, never()).changePassword(any(), any(), any());
    }

    @Test
    void changePasswordServiceErrorReturnsUserKey() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("المستخدم غير موجود"))
                .when(userAdminService).changePassword(eq(CLINIC), eq(userId), any());
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.changePassword(userId, "newpass", session, model);

        assertThat(model.getAttribute("userErrorScope")).isEqualTo("password");
        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).get("user")).isEqualTo("المستخدم غير موجود");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void createUserAlwaysCreatesAndLinksEmployee() {
        HttpSession session = session();
        allowDashboard();
        UUID membershipId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        UserSummary created = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", "a@b.com", "active", "receptionist", membershipId, null);
        when(userAdminService.create(eq(CLINIC), any(UserCreateRequest.class))).thenReturn(created);
        Employee employee = new Employee(employeeId, "أحمد", StaffRole.ASSISTANT,
                null, null, null, null, false, null, null);
        when(employeeService.create(eq(CLINIC), any())).thenReturn(employee);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(created));

        String view = controller.createUser(
                UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123"), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        verify(employeeService).create(eq(CLINIC), any());
        verify(userAdminService).linkEmployee(CLINIC, membershipId, employeeId);
        verify(userAdminService, never()).assignRole(any(), any(), any());
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void createUserValidationErrorsRenderAsFieldError() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.create(eq(CLINIC), any(UserCreateRequest.class)))
                .thenThrow(new UserValidationException(
                        Map.of("email", "البريد الإلكتروني مستخدم بالفعل في هذه العيادة")));
        when(userAdminService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(
                UserAdminController.UserForm.of("ahmed2", "أحمد", "a@b.com", "hash123"), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).get("email"))
                .isEqualTo("البريد الإلكتروني مستخدم بالفعل في هذه العيادة");
        assertThat(model.getAttribute("userErrorScope")).isEqualTo("add");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void createUserDuplicateUsernameReportsError() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.create(eq(CLINIC), any(UserCreateRequest.class)))
                .thenThrow(new IllegalArgumentException("اسم المستخدم موجود مسبقاً"));
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(
                UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123"), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).get("username"))
                .isEqualTo("اسم المستخدم موجود مسبقاً");
        verify(activityLogService, never()).log(any(), any(), any(), any());
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
