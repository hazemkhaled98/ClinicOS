package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertThat(model.getAttribute("addForm")).isNotNull();
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
        when(employeeService.create(eq(CLINIC), any())).thenReturn(new Employee(UUID.randomUUID(), "أحمد",
                null, null, null, null, false, null, null));
        when(userAdminService.list(CLINIC)).thenReturn(List.of(created));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123"), Validated.of(UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123")), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.create", "user");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void createUserValidatesEmptyUsername() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(UserAdminController.UserForm.of("", "أحمد", "a@b.com", "hash123"), Validated.of(UserAdminController.UserForm.of("", "أحمد", "a@b.com", "hash123")), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("اسم المستخدم مطلوب");
        verify(userAdminService, never()).create(any(), any());
    }

    @Test
    void createUserRejectsInvalidEmail() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(UserAdminController.UserForm.of("ahmed", "أحمد", "not-an-email", "hash123"), Validated.of(UserAdminController.UserForm.of("ahmed", "أحمد", "not-an-email", "hash123")), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("صيغة البريد الإلكتروني غير صحيحة");
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

        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).isEqualTo("يمكنك تعليق حسابك الخاص");
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

        controller.assignRole(UUID.randomUUID(), UserAdminController.AssignRoleForm.of("manager", membershipId), Validated.of(UserAdminController.AssignRoleForm.of("manager", membershipId)), session, model);

        verify(userAdminService).assignRole(CLINIC, membershipId, "manager");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.assign_role", "user");
    }

    @Test
    void assignRoleBlankRoleCodeIsRejected() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.assignRole(UUID.randomUUID(),
                UserAdminController.AssignRoleForm.of(" ", UUID.randomUUID()), Validated.of(UserAdminController.AssignRoleForm.of(" ", UUID.randomUUID())), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(userAdminService, never()).assignRole(any(), any(), any());
    }

    @Test
    void changePasswordBlankPasswordReportsError() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.changePassword(userId,
                UserAdminController.PasswordForm.of("  "), Validated.of(UserAdminController.PasswordForm.of("  ")), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(userAdminService, never()).changePassword(any(), any(), any());
    }

    @Test
    void changePasswordServiceErrorReturnsToast() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("المستخدم غير موجود"))
                .when(userAdminService).changePassword(eq(CLINIC), eq(userId), any());
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.changePassword(userId, UserAdminController.PasswordForm.of("newpass"), Validated.of(UserAdminController.PasswordForm.of("newpass")), session, model);

        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).isEqualTo("المستخدم غير موجود");
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
        Employee employee = new Employee(employeeId, "أحمد",
                null, null, null, null, false, null, null);
        when(employeeService.create(eq(CLINIC), any())).thenReturn(employee);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(created));

        String view = controller.createUser(UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123"), Validated.of(UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123")), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        verify(employeeService).create(eq(CLINIC), any());
        verify(userAdminService).linkEmployee(CLINIC, membershipId, employeeId);
        verify(userAdminService, never()).assignRole(any(), any(), any());
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void createUserValidationErrorsRenderAsToast() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.create(eq(CLINIC), any(UserCreateRequest.class)))
                .thenThrow(new UserValidationException(
                        Map.of("email", "البريد الإلكتروني مستخدم بالفعل في هذه العيادة")));
        when(userAdminService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(UserAdminController.UserForm.of("ahmed2", "أحمد", "a@b.com", "hash123"), Validated.of(UserAdminController.UserForm.of("ahmed2", "أحمد", "a@b.com", "hash123")), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(((String) model.getAttribute("toastMessage")))
                .contains("البريد الإلكتروني مستخدم بالفعل في هذه العيادة");
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

        String view = controller.createUser(UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123"), Validated.of(UserAdminController.UserForm.of("ahmed", "أحمد", "a@b.com", "hash123")), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("اسم المستخدم موجود مسبقاً");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
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