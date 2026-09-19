package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.procedures.ProceduresService;
import com.clinicos.procedures.ProceduresService.Procedure;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;

import jakarta.servlet.http.HttpSession;

class ProceduresControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID PROCEDURE = UUID.randomUUID();
    private LayoutModel layoutModel;
    private ProceduresService proceduresService;
    private ActivityLogService activityLogService;
    private EmployeeService employeeService;
    private ProceduresController controller;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        proceduresService = mock(ProceduresService.class);
        activityLogService = mock(ActivityLogService.class);
        employeeService = mock(EmployeeService.class);
        controller = new ProceduresController(layoutModel, proceduresService, activityLogService, employeeService);
    }

    @Test
    void routesRedirectWithoutPermission() {
        HttpSession session = session();
        assertThat(controller.procedures(false, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
        assertThat(controller.myProcedures(null, null, null, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
    }

    @Test
    void proceduresRouteRendersPublishedList() {
        HttpSession session = session("procs");
        when(proceduresService.procedures(CLINIC, false)).thenReturn(List.of(
                new Procedure(PROCEDURE, "حشو", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, List.of(), false)));

        assertThat(controller.procedures(false, session, new ExtendedModelMap())).isEqualTo("procedures");
        verify(proceduresService).procedures(CLINIC, false);
    }

    @Test
    void myProceduresRouteRendersCases() {
        HttpSession session = session("myprocs");
        var employee = new EmployeeService.Employee(UUID.randomUUID(), "assistant", BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, false, null, null);
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(employee);
        when(proceduresService.procedures(CLINIC, false)).thenReturn(List.of());
        when(proceduresService.cases(CLINIC, employee.id(), null, null)).thenReturn(List.of());

        assertThat(controller.myProcedures(null, null, null, session, new ExtendedModelMap())).isEqualTo("my-procedures");
        verify(proceduresService).cases(CLINIC, employee.id(), null, null);
    }

    private HttpSession session(String... codes) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn("assistant");
        when(session.getAttribute(SessionKeys.PERMISSIONS)).thenReturn(List.of(codes));
        return session;
    }
}
