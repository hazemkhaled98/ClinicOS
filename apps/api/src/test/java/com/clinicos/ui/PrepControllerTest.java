package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.prep.PrepChecklistService;
import com.clinicos.prep.PrepChecklistService.Actor;
import com.clinicos.prep.PrepChecklistService.Checklist;
import com.clinicos.prep.PrepChecklistService.Item;
import com.clinicos.prep.PrepChecklistService.Run;
import com.clinicos.prep.PrepChecklistService.Section;
import com.clinicos.prep.PrepChecklistService.Template;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;

import jakarta.servlet.http.HttpSession;

class PrepControllerTest {
    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID EMPLOYEE = UUID.randomUUID();
    private static final UUID CHECKLIST = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();

    private LayoutModel layoutModel;
    private PrepChecklistService checklistService;
    private EmployeeService employeeService;
    private ActivityLogService activityLogService;
    private PrepController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        checklistService = mock(PrepChecklistService.class);
        employeeService = mock(EmployeeService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new PrepController(layoutModel, checklistService, employeeService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void indexRendersClinicChecklists() {
        Checklist checklist = checklist("approved");
        when(checklistService.list(CLINIC)).thenReturn(List.of(checklist));

        String view = controller.index(session("assistant"), model);

        assertThat(view).isEqualTo("prep");
        assertThat(model.getAttribute("checklists")).isEqualTo(List.of(checklist));
        assertThat(model.getAttribute("canApprove")).isEqualTo(false);
    }

    @Test
    void templatesRendersGlobalCatalog() {
        Template template = new Template("examination", "كشف", List.of());
        when(checklistService.templates()).thenReturn(List.of(template));

        String view = controller.templates(session("assistant"), model);

        assertThat(view).isEqualTo("prep-templates");
        assertThat(model.getAttribute("templates")).isEqualTo(List.of(template));
    }

    @Test
    void editLoadsChecklistIntoEditor() {
        Checklist checklist = checklist("draft");
        when(checklistService.get(CLINIC, CHECKLIST)).thenReturn(checklist);

        String view = controller.edit(CHECKLIST, session("assistant"), model);

        assertThat(view).isEqualTo("prep-editor");
        assertThat(model.getAttribute("checklistId")).isEqualTo(CHECKLIST);
        PrepController.ChecklistForm form = (PrepController.ChecklistForm) model.getAttribute("form");
        assertThat(form.getName()).isEqualTo("كشف");
        assertThat(form.getSections()).hasSize(1);
    }

    @Test
    void saveBuildsActorFromSessionAndLinkedEmployee() {
        allowLinkedEmployee();
        PrepController.ChecklistForm form = form();
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.save(null, form, session("assistant"), model, redirect);

        assertThat(view).isEqualTo("redirect:/prep");
        ArgumentCaptor<Actor> actor = ArgumentCaptor.forClass(Actor.class);
        verify(checklistService).save(org.mockito.ArgumentMatchers.eq(CLINIC), actor.capture(),
                org.mockito.ArgumentMatchers.isNull(), any());
        assertThat(actor.getValue()).isEqualTo(new Actor(MEMBERSHIP, "assistant", EMPLOYEE));
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "prep.create", "prep_checklist");
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(redirect).addFlashAttribute("toastMessage", "تمت إضافة القائمة ✔");
    }

    @Test
    void invalidSaveReturnsEditorWithArabicErrorAndSubmittedForm() {
        allowLinkedEmployee();
        PrepController.ChecklistForm form = form();
        when(checklistService.save(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("أضف قسمًا واحدًا على الأقل"));
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.save(CHECKLIST, form, session("assistant"), model, redirect);

        assertThat(view).isEqualTo("prep-editor");
        assertThat(model.getAttribute("form")).isSameAs(form);
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("أضف قسمًا واحدًا على الأقل");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void importTemplateLogsAndRedirectsToEditor() {
        allowLinkedEmployee();
        when(checklistService.importTemplate(any(), any(), any())).thenReturn(checklist("draft"));
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.importTemplate("examination", session("assistant"), model, redirect);

        assertThat(view).isEqualTo("redirect:/prep/checklists/" + CHECKLIST + "/edit");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "prep.import", "prep_checklist");
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(redirect).addFlashAttribute("toastMessage", "تم استيراد القالب ✔");
    }

    @Test
    void approveUsesOwnerActorAndLogs() {
        allowLinkedEmployee();
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.approve(CHECKLIST, session("owner"), model, redirect);

        assertThat(view).isEqualTo("redirect:/prep");
        verify(checklistService).approve(CLINIC, new Actor(MEMBERSHIP, "owner", EMPLOYEE), CHECKLIST);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "prep.approve", "prep_checklist");
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(redirect).addFlashAttribute("toastMessage", "تم اعتماد القائمة ✔");
    }

    @Test
    void unapproveUsesManagerActorAndLogs() {
        allowLinkedEmployee();
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.unapprove(CHECKLIST, session("manager"), model, redirect);

        assertThat(view).isEqualTo("redirect:/prep");
        verify(checklistService).unapprove(CLINIC, new Actor(MEMBERSHIP, "manager", EMPLOYEE), CHECKLIST);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "prep.unapprove", "prep_checklist");
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(redirect).addFlashAttribute("toastMessage", "تم إلغاء الاعتماد");
    }

    @Test
    void archiveUsesOwnerActorLogsAndToasts() {
        allowLinkedEmployee();
        RedirectAttributes redirect = mock(RedirectAttributes.class);

        String view = controller.archive(CHECKLIST, session("owner"), model, redirect);

        assertThat(view).isEqualTo("redirect:/prep");
        verify(checklistService).archive(CLINIC, new Actor(MEMBERSHIP, "owner", EMPLOYEE), CHECKLIST);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "prep.archive", "prep_checklist");
        verify(redirect).addFlashAttribute("toastType", "success");
        verify(redirect).addFlashAttribute("toastMessage", "تم أرشفة القائمة");
    }

    @Test
    void runAndToggleRenderCurrentProgress() {
        allowLinkedEmployee();
        Run run = new Run(UUID.randomUUID(), LocalDate.now(), 1, 1, checklist("approved").sections());
        when(checklistService.today(any(), any(), any())).thenReturn(run);
        when(checklistService.toggle(any(), any(), any(), any(), any(Boolean.class))).thenReturn(run);

        String page = controller.run(CHECKLIST, session("assistant"), model);
        String fragment = controller.toggle(CHECKLIST, ITEM, true, session("assistant"), model);

        assertThat(page).isEqualTo("prep-run");
        assertThat(fragment).isEqualTo("prep-run :: runContent");
        assertThat(model.getAttribute("run")).isEqualTo(run);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "prep.toggle", "prep_run_item");
    }

    @Test
    void resetRendersRunFragmentAndLogs() {
        allowLinkedEmployee();
        Run run = new Run(UUID.randomUUID(), LocalDate.now(), 0, 1, checklist("approved").sections());
        when(checklistService.reset(any(), any(), any())).thenReturn(run);

        String view = controller.reset(CHECKLIST, session("assistant"), model);

        assertThat(view).isEqualTo("prep-run :: runContent");
        assertThat(model.getAttribute("run")).isEqualTo(run);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "prep.reset", "prep_run");
    }

    @Test
    void toggleReturnsClientErrorInsteadOfRenderingRunFragmentWithoutRun() {
        allowLinkedEmployee();
        when(checklistService.toggle(any(), any(), any(), any(), any(Boolean.class)))
                .thenThrow(new IllegalArgumentException("القائمة غير موجودة"));

        assertThatThrownBy(() -> controller.toggle(CHECKLIST, ITEM, true, session("assistant"), model))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void resetReturnsClientErrorInsteadOfRenderingRunFragmentWithoutRun() {
        allowLinkedEmployee();
        when(checklistService.reset(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("القائمة غير موجودة"));

        assertThatThrownBy(() -> controller.reset(CHECKLIST, session("assistant"), model))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void missingSessionRedirectsWithoutCallingService() {
        HttpSession session = mock(HttpSession.class);

        assertThat(controller.index(session, model)).isEqualTo("redirect:/login");
        assertThat(controller.templates(session, model)).isEqualTo("redirect:/login");
        assertThat(controller.run(CHECKLIST, session, model)).isEqualTo("redirect:/login");
        verify(checklistService, never()).list(any());
        verify(checklistService, never()).templates();
        verify(checklistService, never()).today(any(), any(), any());
    }

    private void allowLinkedEmployee() {
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(new Employee(
                EMPLOYEE, "مساعد", BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.MIN, LocalTime.MAX,
                true, LocalDate.now(), null));
    }

    private static HttpSession session(String roleCode) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn(roleCode);
        return session;
    }

    private static Checklist checklist(String status) {
        return new Checklist(CHECKLIST, "كشف", status, null,
                List.of(new Section(UUID.randomUUID(), "عام", List.of(new Item(ITEM, "قفازات", false)))));
    }

    private static PrepController.ChecklistForm form() {
        PrepController.ChecklistForm form = new PrepController.ChecklistForm();
        form.setName("كشف");
        PrepController.SectionForm section = new PrepController.SectionForm();
        section.setTitle("عام");
        PrepController.ItemForm item = new PrepController.ItemForm();
        item.setName("قفازات");
        section.setItems(List.of(item));
        form.setSections(List.of(section));
        return form;
    }
}
