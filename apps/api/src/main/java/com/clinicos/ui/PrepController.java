package com.clinicos.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.prep.api.PrepChecklistService;
import com.clinicos.prep.api.PrepChecklistService.Actor;
import com.clinicos.prep.api.PrepChecklistService.Checklist;
import com.clinicos.prep.api.PrepChecklistService.ChecklistRequest;
import com.clinicos.prep.api.PrepChecklistService.ItemRequest;
import com.clinicos.prep.api.PrepChecklistService.SectionRequest;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;

import jakarta.servlet.http.HttpSession;

@Controller
public class PrepController {
    private static final String INDEX = "prep";
    private static final String EDITOR = "prep-editor";
    private static final String RUN = "prep-run";
    private static final String RUN_CONTENT = "prep-run :: runContent";

    private final LayoutModel layoutModel;
    private final PrepChecklistService checklistService;
    private final EmployeeService employeeService;
    private final ActivityLogService activityLogService;

    public PrepController(LayoutModel layoutModel, PrepChecklistService checklistService,
            EmployeeService employeeService, ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.checklistService = checklistService;
        this.employeeService = employeeService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/prep")
    public String index(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        renderIndex(session, model);
        return INDEX;
    }

    @GetMapping("/prep/templates")
    public String templates(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "prep"));
        model.addAttribute("templates", checklistService.templates());
        return "prep-templates";
    }

    @PostMapping("/prep/templates/{code}/import")
    public String importTemplate(@PathVariable String code, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            Checklist checklist = checklistService.importTemplate(clinicId(session), actor(session), code);
            log(session, "prep.import", "prep_checklist");
            return "redirect:/prep/checklists/" + checklist.id() + "/edit";
        } catch (IllegalArgumentException exception) {
            model.addAttribute("layout", layoutModel.forRequest(session, "prep"));
            model.addAttribute("templates", checklistService.templates());
            model.addAttribute("formError", exception.getMessage());
            return "prep-templates";
        }
    }

    @GetMapping("/prep/checklists/new")
    public String create(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        renderEditor(session, model, null, new ChecklistForm());
        return EDITOR;
    }

    @GetMapping("/prep/checklists/{id}/edit")
    public String edit(@PathVariable UUID id, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            renderEditor(session, model, id, ChecklistForm.from(checklistService.get(clinicId(session), id)));
            return EDITOR;
        } catch (IllegalArgumentException exception) {
            renderIndex(session, model);
            Toasts.error(model, exception.getMessage());
            return INDEX;
        }
    }

    @PostMapping({"/prep/checklists", "/prep/checklists/{id}"})
    public String save(@PathVariable(required = false) UUID id, @ModelAttribute("form") ChecklistForm form,
            HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            checklistService.save(clinicId(session), actor(session), id, form.toRequest());
            log(session, id == null ? "prep.create" : "prep.edit", "prep_checklist");
            return "redirect:/prep";
        } catch (IllegalArgumentException exception) {
            renderEditor(session, model, id, form);
            model.addAttribute("formError", exception.getMessage());
            return EDITOR;
        }
    }

    @PostMapping("/prep/checklists/{id}/approve")
    public String approve(@PathVariable UUID id, HttpSession session, Model model) {
        return transition(id, session, model, "prep.approve",
                actor -> checklistService.approve(clinicId(session), actor, id));
    }

    @PostMapping("/prep/checklists/{id}/unapprove")
    public String unapprove(@PathVariable UUID id, HttpSession session, Model model) {
        return transition(id, session, model, "prep.unapprove",
                actor -> checklistService.unapprove(clinicId(session), actor, id));
    }

    @PostMapping("/prep/checklists/{id}/archive")
    public String archive(@PathVariable UUID id, HttpSession session, Model model) {
        return transition(id, session, model, "prep.archive", actor -> {
            checklistService.archive(clinicId(session), actor, id);
            return null;
        });
    }

    @GetMapping("/prep/checklists/{id}/run")
    public String run(@PathVariable UUID id, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            model.addAttribute("layout", layoutModel.forRequest(session, "prep"));
            model.addAttribute("checklistId", id);
            model.addAttribute("run", checklistService.today(clinicId(session), actor(session), id));
            return RUN;
        } catch (IllegalArgumentException exception) {
            renderIndex(session, model);
            Toasts.error(model, exception.getMessage());
            return INDEX;
        }
    }

    @PostMapping("/prep/checklists/{id}/run/items/{itemId}")
    public String toggle(@PathVariable UUID id, @PathVariable UUID itemId,
            @RequestParam(defaultValue = "false") boolean checked, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            model.addAttribute("checklistId", id);
            model.addAttribute("run", checklistService.toggle(clinicId(session), actor(session), id, itemId, checked));
            log(session, "prep.toggle", "prep_run_item");
        } catch (IllegalArgumentException exception) {
            Toasts.error(model, exception.getMessage());
        }
        return RUN_CONTENT;
    }

    @PostMapping("/prep/checklists/{id}/run/reset")
    public String reset(@PathVariable UUID id, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            model.addAttribute("checklistId", id);
            model.addAttribute("run", checklistService.reset(clinicId(session), actor(session), id));
            log(session, "prep.reset", "prep_run");
        } catch (IllegalArgumentException exception) {
            Toasts.error(model, exception.getMessage());
        }
        return RUN_CONTENT;
    }

    private String transition(UUID id, HttpSession session, Model model, String action,
            java.util.function.Function<Actor, Checklist> operation) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            operation.apply(actor(session));
            log(session, action, "prep_checklist");
            return "redirect:/prep";
        } catch (IllegalArgumentException exception) {
            renderIndex(session, model);
            Toasts.error(model, exception.getMessage());
            return INDEX;
        }
    }

    private void renderIndex(HttpSession session, Model model) {
        model.addAttribute("layout", layoutModel.forRequest(session, "prep"));
        model.addAttribute("checklists", checklistService.list(clinicId(session)));
        model.addAttribute("canApprove", canApprove(session));
    }

    private void renderEditor(HttpSession session, Model model, UUID checklistId, ChecklistForm form) {
        model.addAttribute("layout", layoutModel.forRequest(session, "prep"));
        model.addAttribute("checklistId", checklistId);
        model.addAttribute("form", form);
    }

    private Actor actor(HttpSession session) {
        UUID clinicId = clinicId(session);
        UUID membershipId = (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID);
        Employee employee = employeeService.findByMembership(clinicId, membershipId);
        if (employee == null) {
            throw new IllegalArgumentException("حسابك غير مرتبط بملف موظف");
        }
        return new Actor(membershipId, (String) session.getAttribute(SessionKeys.ROLE_CODE), employee.id());
    }

    private void log(HttpSession session, String action, String entityType) {
        activityLogService.log(clinicId(session),
                (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID), action, entityType);
    }

    private static boolean hasSession(HttpSession session) {
        return session.getAttribute(SessionKeys.CLINIC_ID) instanceof UUID
                && session.getAttribute(SessionKeys.MEMBERSHIP_ID) instanceof UUID
                && session.getAttribute(SessionKeys.ROLE_CODE) instanceof String;
    }

    private static UUID clinicId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.CLINIC_ID);
    }

    private static boolean canApprove(HttpSession session) {
        String role = (String) session.getAttribute(SessionKeys.ROLE_CODE);
        return "owner".equals(role) || "manager".equals(role);
    }

    public static class ChecklistForm {
        private String name;
        private List<SectionForm> sections = new ArrayList<>();

        static ChecklistForm from(Checklist checklist) {
            ChecklistForm form = new ChecklistForm();
            form.name = checklist.name();
            form.sections = checklist.sections().stream().map(SectionForm::from).toList();
            return form;
        }

        ChecklistRequest toRequest() {
            return new ChecklistRequest(name, sections.stream().map(SectionForm::toRequest).toList());
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<SectionForm> getSections() {
            return sections;
        }

        public void setSections(List<SectionForm> sections) {
            this.sections = sections == null ? new ArrayList<>() : sections;
        }
    }

    public static class SectionForm {
        private String title;
        private List<ItemForm> items = new ArrayList<>();

        static SectionForm from(PrepChecklistService.Section section) {
            SectionForm form = new SectionForm();
            form.title = section.title();
            form.items = section.items().stream().map(ItemForm::from).toList();
            return form;
        }

        SectionRequest toRequest() {
            return new SectionRequest(title, items.stream().map(ItemForm::toRequest).toList());
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public List<ItemForm> getItems() {
            return items;
        }

        public void setItems(List<ItemForm> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }
    }

    public static class ItemForm {
        private String name;

        static ItemForm from(PrepChecklistService.Item item) {
            ItemForm form = new ItemForm();
            form.name = item.name();
            return form;
        }

        ItemRequest toRequest() {
            return new ItemRequest(name);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
