package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.LeaveRequestService;

import jakarta.servlet.http.HttpSession;

class LeaveControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();

    private EmployeeService employeeService;
    private LeaveRequestService leaveRequests;
    private LeaveController controller;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        employeeService = mock(EmployeeService.class);
        leaveRequests = mock(LeaveRequestService.class);
        controller = new LeaveController(mock(LayoutModel.class), leaveRequests, employeeService);
        session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn("assistant");
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(new Employee(
                EMPLOYEE_ID, "أحمد", new BigDecimal("5000"), new BigDecimal("500"), null, null, false, null, null));
    }

    @Test
    void staffCannotOpenApproverQueue() {
        String view = controller.queue(session, new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/leaves/me");
    }

    @Test
    void soleOwnerIsDirectedToTheHolidayCalendar() {
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn("owner");
        when(leaveRequests.hasOtherActiveOwner(CLINIC, MEMBERSHIP)).thenReturn(false);
        when(leaveRequests.listPendingForApprover(CLINIC, MEMBERSHIP)).thenReturn(java.util.List.of());
        when(leaveRequests.listForEmployee(CLINIC, EMPLOYEE_ID)).thenReturn(java.util.List.of());
        Model model = new ExtendedModelMap();

        assertThat(controller.mine(session, model)).isEqualTo("leaves-me");
        assertThat(model.getAttribute("soleOwner")).isEqualTo(true);
        assertThat(model.getAttribute("canRequest")).isEqualTo(false);
    }

    @Test
    void ownerCanRequestWhenAnotherActiveOwnerCanDecide() {
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn("owner");
        when(leaveRequests.hasOtherActiveOwner(CLINIC, MEMBERSHIP)).thenReturn(true);
        when(leaveRequests.listPendingForApprover(CLINIC, MEMBERSHIP)).thenReturn(java.util.List.of());
        when(leaveRequests.listForEmployee(CLINIC, EMPLOYEE_ID)).thenReturn(java.util.List.of());
        Model model = new ExtendedModelMap();

        assertThat(controller.mine(session, model)).isEqualTo("leaves-me");
        assertThat(model.getAttribute("soleOwner")).isEqualTo(false);
        assertThat(model.getAttribute("canRequest")).isEqualTo(true);
    }

    @Test
    void submitsValidDateRangeForOwnEmployee() {
        Model model = new ExtendedModelMap();
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        LocalDate start = LocalDate.parse("2026-10-12");
        LocalDate end = LocalDate.parse("2026-10-14");
        String view = controller.submit("2026-10-12", "2026-10-14", "راحة", session, model, redirect);

        assertThat(view).isEqualTo("redirect:/leaves/me");
        verify(leaveRequests).submit(eq(CLINIC), eq(EMPLOYEE_ID), eq(start), eq(end), eq("راحة"), eq(MEMBERSHIP));
    }

    @Test
    void overlappingSubmissionKeepsValuesAndShowsAnInlineError() {
        LocalDate start = LocalDate.parse("2026-10-05");
        LocalDate end = LocalDate.parse("2026-10-06");
        when(leaveRequests.submit(CLINIC, EMPLOYEE_ID, start, end, "تكرار", MEMBERSHIP))
                .thenThrow(new IllegalArgumentException("توجد إجازة مسجلة بنفس الفترة"));
        Model model = new ExtendedModelMap();

        String view = controller.submit("2026-10-05", "2026-10-06", "تكرار", session, model,
                mock(RedirectAttributes.class));

        assertThat(view).isEqualTo("leaves-me");
        assertThat(model.getAttribute("formError")).isEqualTo("توجد إجازة مسجلة بنفس الفترة");
        assertThat(model.getAttribute("start")).isEqualTo("2026-10-05");
        assertThat(model.getAttribute("end")).isEqualTo("2026-10-06");
        assertThat(model.getAttribute("reason")).isEqualTo("تكرار");
    }
}
