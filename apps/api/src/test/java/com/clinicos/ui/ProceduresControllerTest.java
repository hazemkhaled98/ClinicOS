package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.clinicos.procedures.ProceduresService.BomLineRequest;
import com.clinicos.procedures.ProceduresService.Procedure;
import com.clinicos.procedures.ProceduresService.ProcedureRequest;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.staff.api.EmployeeService;

import jakarta.servlet.http.HttpSession;

class ProceduresControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID PROCEDURE = UUID.randomUUID();
    private static final UUID ITEM_SQ = UUID.randomUUID();
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

    @Test
    void createProcedurePostsLogsAndFlashesSuccess() {
        HttpSession session = session("procs");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        ProceduresController.ProcedureForm form = new ProceduresController.ProcedureForm();
        form.setName("حشو");
        form.setPrice(new BigDecimal("100"));
        form.setLaborCost(BigDecimal.ZERO);
        form.setDoctorFee(BigDecimal.ZERO);
        var request = new ProcedureRequest("حشو", new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        String view = controller.create(form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/procs");
        verify(proceduresService).createProcedure(CLINIC, new com.clinicos.inventory.InventoryService.Actor(MEMBERSHIP, "assistant"), request);
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.procedure.create", "procedure");
    }

    @Test
    void createProcedureServiceRejectionFlashesErrorAndDoesNotLog() {
        HttpSession session = session("procs");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        when(proceduresService.createProcedure(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("اسم الإجراء والأسعار مطلوبة"));
        ProceduresController.ProcedureForm form = new ProceduresController.ProcedureForm();

        String view = controller.create(form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/procs");
        verify(redirect).addFlashAttribute("toastType", "error");
        verify(redirect).addFlashAttribute("toastMessage", "اسم الإجراء والأسعار مطلوبة");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void changePostsEditRequestLogsAndFlashesWaitingToast() {
        HttpSession session = session("procs");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        ProceduresController.ProcedureForm form = new ProceduresController.ProcedureForm();
        form.setName("حشو معدّل");
        form.setPrice(new BigDecimal("150"));
        var request = new ProcedureRequest("حشو معدّل", new BigDecimal("150"), null, null, List.of());
        when(proceduresService.requestProcedureChange(any(), any(), eq(PROCEDURE), eq(ChangeRequestKind.edit), any()))
                .thenReturn(UUID.randomUUID());

        String view = controller.change(PROCEDURE, "edit", form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/procs");
        verify(proceduresService).requestProcedureChange(CLINIC,
                new com.clinicos.inventory.InventoryService.Actor(MEMBERSHIP, "assistant"), PROCEDURE,
                ChangeRequestKind.edit, request);
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.procedure.request-change", "inventory_change_request");
    }

    @Test
    void changeWithInvalidKindFlashesErrorAndDoesNotLog() {
        HttpSession session = session("procs");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        ProceduresController.ProcedureForm form = new ProceduresController.ProcedureForm();

        String view = controller.change(PROCEDURE, "bogus", form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/procs");
        verify(redirect).addFlashAttribute("toastType", "error");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void bomChangePostsLinesLogsAndFlashesSuccess() {
        HttpSession session = session("procs");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        when(proceduresService.requestProcedureBomChange(any(), any(), eq(PROCEDURE), any()))
                .thenReturn(UUID.randomUUID());

        String view = controller.bomChange(PROCEDURE, List.of(ITEM_SQ), List.of(new BigDecimal("2")), session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/procs");
        verify(proceduresService).requestProcedureBomChange(CLINIC,
                new com.clinicos.inventory.InventoryService.Actor(MEMBERSHIP, "assistant"), PROCEDURE,
                List.of(new BomLineRequest(ITEM_SQ, new BigDecimal("2"))));
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.procedure.request-bom-change", "inventory_change_request");
    }

    @Test
    void recordCasePostsLogsAndFlashesSuccess() {
        HttpSession session = session("myprocs");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        var employee = new EmployeeService.Employee(UUID.randomUUID(), "assistant", BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, false, null, null);
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(employee);
        when(proceduresService.recordCase(any(), any(), any())).thenReturn(null);
        ProceduresController.CaseForm form = new ProceduresController.CaseForm();
        form.setProcedureId(PROCEDURE);
        form.setDoctorName("د. أحمد");

        String view = controller.record(form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/myprocs");
        verify(proceduresService).recordCase(CLINIC,
                new com.clinicos.inventory.InventoryService.Actor(MEMBERSHIP, "assistant"),
                new com.clinicos.procedures.ProceduresService.CaseDraft(PROCEDURE, employee.id(), "د. أحمد", null, List.of()));
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "inventory.procedure-case.create", "procedure_case");
    }

    @Test
    void recordCaseWithNoLinkedEmployeeFlashesErrorAndDoesNotLog() {
        HttpSession session = session("myprocs");
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(null);
        ProceduresController.CaseForm form = new ProceduresController.CaseForm();

        String view = controller.record(form, session, redirect);

        assertThat(view).isEqualTo("redirect:/inventory/myprocs");
        verify(redirect).addFlashAttribute("toastType", "error");
        verify(redirect).addFlashAttribute("toastMessage", "المستخدم غير مرتبط بموظف");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void everyPostRouteRedirectsWithoutItsPermission() {
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        HttpSession session = session();
        assertThat(controller.create(new ProceduresController.ProcedureForm(), session, redirect)).isEqualTo("redirect:/inventory");
        assertThat(controller.change(PROCEDURE, "edit", new ProceduresController.ProcedureForm(), session, redirect)).isEqualTo("redirect:/inventory");
        assertThat(controller.bomChange(PROCEDURE, List.of(), List.of(), session, redirect)).isEqualTo("redirect:/inventory");
        assertThat(controller.record(new ProceduresController.CaseForm(), session, redirect)).isEqualTo("redirect:/inventory");
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
