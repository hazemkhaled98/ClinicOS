package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettings;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.AttachmentService;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.SelfCheckService;
import com.clinicos.staff.api.SelfCheckService.DayAttendance;
import com.clinicos.staff.api.TaskAssignmentService;
import com.clinicos.staff.api.TaskAssignmentService.Assignment;
import com.clinicos.staff.api.TaskAssignmentService.Proposer;
import com.clinicos.ui.EmployeeDayController.DayView;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class EmployeeDayControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID EMPLOYEE = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ASSIGNMENT = UUID.randomUUID();
    private static final UUID PHOTO = UUID.randomUUID();

    private LayoutModel layoutModel;
    private EmployeeService employeeService;
    private SelfCheckService selfCheckService;
    private DailyWorkService dailyWorkService;
    private TaskAssignmentService assignmentService;
    private ClinicSettingsService clinicSettingsService;
    private WorkCalendarService workCalendarService;
    private AttachmentService attachmentService;
    private ActivityLogService activityLogService;
    private EmployeeDayController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        employeeService = mock(EmployeeService.class);
        selfCheckService = mock(SelfCheckService.class);
        dailyWorkService = mock(DailyWorkService.class);
        assignmentService = mock(TaskAssignmentService.class);
        clinicSettingsService = mock(ClinicSettingsService.class);
        workCalendarService = mock(WorkCalendarService.class);
        attachmentService = mock(AttachmentService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new EmployeeDayController(layoutModel, employeeService, selfCheckService,
                dailyWorkService, assignmentService, clinicSettingsService, workCalendarService,
                attachmentService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void employeesWithoutLinkedEmployeeRendersEmptyState() {
        HttpSession session = session();
        allowEmployees();

        String view = controller.employees(session, model);

        assertThat(view).isEqualTo("employees-page");
        DayView day = (DayView) model.getAttribute("day");
        assertThat(day.noEmployee()).isTrue();
    }

    @Test
    void employeesLocksTasksUntilCheckIn() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        when(selfCheckService.today(CLINIC, EMPLOYEE))
                .thenReturn(new DayAttendance(LocalDate.now(), null, null));

        String view = controller.employees(session, model);

        assertThat(view).isEqualTo("employees-page");
        DayView day = (DayView) model.getAttribute("day");
        assertThat(day.noEmployee()).isFalse();
        assertThat(day.locked()).isTrue();
        assertThat(day.attendance().checkedIn()).isFalse();
    }

    @Test
    void checkInRendersSuccessToastAndUnlockedGrid() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        DayAttendance checkedIn =
                new DayAttendance(LocalDate.now(), OffsetDateTime.now(), null);
        when(selfCheckService.checkIn(CLINIC, EMPLOYEE)).thenReturn(checkedIn);
        when(selfCheckService.today(CLINIC, EMPLOYEE)).thenReturn(checkedIn);

        String view = controller.checkIn(session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat((String) model.getAttribute("toastMessage")).contains("تم تسجيل الحضور");
        DayView day = (DayView) model.getAttribute("day");
        assertThat(day.locked()).isFalse();
        assertThat(day.attendance().checkedIn()).isTrue();
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "selfcheck.checkin", "self_check");
    }

    @Test
    void checkInServiceErrorSurfacesArabicToast() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        doThrow(new IllegalArgumentException("تعذّر تسجيل الحضور"))
                .when(selfCheckService).checkIn(CLINIC, EMPLOYEE);

        String view = controller.checkIn(session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat((String) model.getAttribute("toastMessage")).contains("تعذّر تسجيل الحضور");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void completeTaskWithoutPhotoSurfacesArabicValidationError() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        doThrow(new IllegalArgumentException("لازم ترفق صورة لإثبات هذه المهمة"))
                .when(dailyWorkService).complete(eq(CLINIC), eq(EMPLOYEE), eq(TASK), isNull());

        String view = controller.completeTask(TASK, null, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat((String) model.getAttribute("toastMessage")).contains("لازم ترفق صورة لإثبات هذه المهمة");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void blankProposeNameIsRejectedByNotBlankConstraint() {
        EmployeeDayController.AssignmentFormDto form = new EmployeeDayController.AssignmentFormDto();

        BindingResult binding = Validated.of(form);

        assertThat(binding.hasFieldErrors("name")).isTrue();
        assertThat(binding.getFieldError("name").getDefaultMessage()).isEqualTo("اسم المهمة مطلوب");
    }

    @Test
    void proposeAssignmentRendersPendingAssignmentAndToast() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        Assignment pending = new Assignment(UUID.randomUUID(), "ترتيب ملفات المرضى",
                LocalDate.now(), "pending", null, null, null, null);
        when(assignmentService.propose(eq(CLINIC), eq(EMPLOYEE), any(), eq(Proposer.SELF)))
                .thenReturn(pending);
        when(assignmentService.today(CLINIC, EMPLOYEE)).thenReturn(List.of(pending));
        var form = new EmployeeDayController.AssignmentFormDto();
        form.setName("ترتيب ملفات المرضى");

        String view = controller.proposeAssignment(form, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat((String) model.getAttribute("toastMessage")).contains("تم إرسال الاقتراح للمدير");
        DayView day = (DayView) model.getAttribute("day");
        assertThat(day.assignments()).extracting("statusLabel")
                .contains("في انتظار موافقة المدير");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "assignment.propose", "task_assignment");
    }

    @Test
    void proposeServiceErrorSurfacesArabicToast() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        doThrow(new IllegalArgumentException("اسم المهمة مطلوب"))
                .when(assignmentService).propose(eq(CLINIC), eq(EMPLOYEE), any(), eq(Proposer.SELF));
        var form = new EmployeeDayController.AssignmentFormDto();
        form.setName("   ");

        String view = controller.proposeAssignment(form, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat((String) model.getAttribute("toastMessage")).contains("اسم المهمة مطلوب");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void completeTaskWithoutPhotoRendersSuccessToast() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();

        String view = controller.completeTask(TASK, null, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat((String) model.getAttribute("toastMessage")).contains("تم تأكيد المهمة");
        verify(dailyWorkService).complete(CLINIC, EMPLOYEE, TASK, null);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "task.complete", "daily_task_completion");
        verify(attachmentService, never()).upload(any(), any(), any());
        verify(attachmentService, never()).delete(any(), any());
    }

    @Test
    void completeTaskWithPhotoRendersSuccessToastAndUploads() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        MultipartFile photo = mock(MultipartFile.class);
        when(photo.isEmpty()).thenReturn(false);
        when(attachmentService.upload(eq(CLINIC), eq(MEMBERSHIP), eq(photo)))
                .thenReturn(new AttachmentService.Attachment(PHOTO, "k", "image/jpeg", 10));

        String view = controller.completeTask(TASK, photo, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat((String) model.getAttribute("toastMessage")).contains("تم تأكيد المهمة");
        verify(dailyWorkService).complete(CLINIC, EMPLOYEE, TASK, PHOTO);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "task.complete", "daily_task_completion");
        verify(attachmentService, never()).delete(any(), any());
    }

    @Test
    void completeTaskServiceErrorAfterUploadDeletesAttachment() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        MultipartFile photo = mock(MultipartFile.class);
        when(photo.isEmpty()).thenReturn(false);
        when(attachmentService.upload(eq(CLINIC), eq(MEMBERSHIP), eq(photo)))
                .thenReturn(new AttachmentService.Attachment(PHOTO, "k", "image/jpeg", 10));
        doThrow(new IllegalArgumentException("المهمة غير موجودة"))
                .when(dailyWorkService).complete(CLINIC, EMPLOYEE, TASK, PHOTO);

        String view = controller.completeTask(TASK, photo, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat((String) model.getAttribute("toastMessage")).contains("المهمة غير موجودة");
        verify(attachmentService).delete(CLINIC, PHOTO);
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void uncompleteTaskRendersSuccessToast() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();

        String view = controller.uncompleteTask(TASK, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat((String) model.getAttribute("toastMessage")).contains("تم إلغاء تأكيد المهمة");
        verify(dailyWorkService).uncomplete(CLINIC, EMPLOYEE, TASK);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "task.uncomplete", "daily_task_completion");
    }

    @Test
    void assignmentDoneRendersSuccessToast() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();

        String view = controller.assignmentDone(ASSIGNMENT, null, session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat((String) model.getAttribute("toastMessage")).contains("تم تسليم المهمة");
        verify(assignmentService).markDone(CLINIC, EMPLOYEE, ASSIGNMENT, null);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "assignment.done", "task_assignment");
    }

    @Test
    void checkOutRendersSuccessToast() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();

        String view = controller.checkOut(session, model);

        assertThat(view).isEqualTo("employees :: dayGrid");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        assertThat((String) model.getAttribute("toastMessage")).contains("تم تسجيل الانصراف");
        verify(selfCheckService).checkOut(CLINIC, EMPLOYEE);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "selfcheck.checkout", "self_check");
    }

    @Test
    void employeesDayOffWhenNotWorkday() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee();
        when(workCalendarService.isWorkday(eq(CLINIC), any(), eq(EMPLOYEE))).thenReturn(false);

        String view = controller.employees(session, model);

        assertThat(view).isEqualTo("employees-page");
        DayView day = (DayView) model.getAttribute("day");
        assertThat(day.dayOff()).isTrue();
    }

    @Test
    void employeesShiftOverWithCustomShiftEndedAtMidnight() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee(new Employee(EMPLOYEE, "محمود", null, null,
                LocalTime.of(9, 0), LocalTime.MIN, true, null, null));

        String view = controller.employees(session, model);

        assertThat(view).isEqualTo("employees-page");
        DayView day = (DayView) model.getAttribute("day");
        assertThat(day.shiftOver()).isTrue();
    }

    @Test
    void employeesShiftNotOverWithCustomShiftEndingAtMax() {
        HttpSession session = session();
        allowEmployees();
        stubLinkedEmployee(new Employee(EMPLOYEE, "محمود", null, null,
                LocalTime.of(9, 0), LocalTime.MAX, true, null, null));

        String view = controller.employees(session, model);

        assertThat(view).isEqualTo("employees-page");
        DayView day = (DayView) model.getAttribute("day");
        assertThat(day.shiftOver()).isFalse();
    }

    @Test
    void withoutEmployeesSectionEveryRouteRedirectsHome() {
        HttpSession session = session();
        denyEmployees();

        assertThat(controller.employees(session, model)).isEqualTo("redirect:/");
        assertThat(controller.checkIn(session, model)).isEqualTo("redirect:/");
        assertThat(controller.checkOut(session, model)).isEqualTo("redirect:/");
        assertThat(controller.completeTask(TASK, null, session, model)).isEqualTo("redirect:/");
        assertThat(controller.uncompleteTask(TASK, session, model)).isEqualTo("redirect:/");
        assertThat(controller.assignmentDone(UUID.randomUUID(), null, session, model))
                .isEqualTo("redirect:/");
        assertThat(controller.proposeAssignment(new EmployeeDayController.AssignmentFormDto(),
                session, model)).isEqualTo("redirect:/");
        verify(employeeService, never()).findByMembership(any(), any());
        verify(selfCheckService, never()).checkIn(any(), any());
        verify(dailyWorkService, never()).complete(any(), any(), any(), any());
        verify(assignmentService, never()).propose(any(), any(), any(), any());
    }

    private void stubLinkedEmployee() {
        stubLinkedEmployee(new Employee(EMPLOYEE, "محمود", null, null, null, null, false, null, null));
    }

    private void stubLinkedEmployee(Employee employee) {
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(employee);
        when(clinicSettingsService.get(CLINIC)).thenReturn(settings());
        when(workCalendarService.isWorkday(eq(CLINIC), any(), eq(EMPLOYEE))).thenReturn(true);
        when(selfCheckService.today(CLINIC, EMPLOYEE))
                .thenReturn(new DayAttendance(LocalDate.now(), OffsetDateTime.now(), null));
        when(dailyWorkService.today(CLINIC, EMPLOYEE)).thenReturn(List.of());
        when(assignmentService.today(CLINIC, EMPLOYEE)).thenReturn(List.of());
    }

    private static ClinicSettings settings() {
        return new ClinicSettings(LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 26,
                new BigDecimal("20000"), 70, List.of(), List.of(), true);
    }

    private void allowEmployees() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("employees")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(NavSectionResolver.sectionByRoute("employees")),
                        "أحمد", "عيادتي", "مساعد", "19 مايو 2026", "employees"));
    }

    private void denyEmployees() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("employees")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "عيادتي", "مساعد",
                        "19 مايو 2026", "employees"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}
