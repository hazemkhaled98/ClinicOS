package com.clinicos.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.clinicos.academy.AcademyService;
import com.clinicos.academy.AcademyService.Actor;
import com.clinicos.academy.AcademyService.Submission;
import com.clinicos.academy.AcademyService.Unit;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.AttachmentService;
import com.clinicos.shared.jooq.enums.AcademyAudience;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;

import jakarta.servlet.http.HttpSession;

@Controller
public class AcademyController {
    private static final String INDEX = "academy";
    private static final String LEARNER = "academy-learner";
    private static final String EXAM = "academy-exam";
    private static final String CERT = "academy-certificate";
    private static final String QUEUE = "academy-verify";
    private static final String CURRICULUM = "academy-curriculum";
    private static final String UNIT_EDITOR = "academy-unit-editor";

    private final LayoutModel layoutModel;
    private final AcademyService academyService;
    private final EmployeeService employeeService;
    private final AttachmentService attachmentService;
    private final ActivityLogService activityLogService;

    public AcademyController(LayoutModel layoutModel, AcademyService academyService,
            EmployeeService employeeService, AttachmentService attachmentService,
            ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.academyService = academyService;
        this.employeeService = employeeService;
        this.attachmentService = attachmentService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/academy")
    public String index(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        renderIndex(session, model);
        return INDEX;
    }

    @GetMapping("/academy/me")
    public String myLearning(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        return learner(employeeId(session), session, model);
    }

    @GetMapping("/academy/learners")
    public String learners(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        renderIndex(session, model);
        return INDEX;
    }

    @GetMapping("/academy/learners/{employeeId}")
    public String learner(@PathVariable UUID employeeId, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        if (employeeId.equals(employeeId(session))) {
            model.addAttribute("track", new AcademyService.TraineeTrack(
                    employeeId, employeeName(session),
                    academyService.myCurriculum(clinicId(session), actor(session))));
        } else {
            var audience = academyService.audienceOf(clinicId(session), employeeId);
            model.addAttribute("track",
                    academyService.traineeCurriculum(clinicId(session), actor(session), employeeId, audience));
        }
        return LEARNER;
    }

    @PostMapping("/academy/units/{unitId}/photo")
    public String submitPhoto(@PathVariable UUID unitId, @RequestParam("photo") MultipartFile photo,
            HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        UUID photoId = null;
        try {
            photoId = attachmentService.upload(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    photo).id();
            academyService.submitPhoto(clinicId(session), actor(session), unitId, photoId);
            activityLogService.log(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    "academy.submitPhoto", "academy_step_submission");
        } catch (IllegalArgumentException e) {
            if (photoId != null) {
                attachmentService.delete(clinicId(session), photoId);
            }
            Toasts.error(model, e.getMessage());
        }
        return redirectToMe(model, session);
    }

    @PostMapping("/academy/units/{unitId}/done")
    public String markDone(@PathVariable UUID unitId, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            academyService.markDone(clinicId(session), actor(session), unitId);
            activityLogService.log(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    "academy.markDone", "academy_step_submission");
        } catch (IllegalArgumentException e) {
            Toasts.error(model, e.getMessage());
        }
        return redirectToMe(model, session);
    }

    @GetMapping("/academy/exam")
    public String exam(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        try {
            model.addAttribute("exam", academyService.exam(clinicId(session), actor(session)));
            return EXAM;
        } catch (IllegalArgumentException e) {
            renderIndex(session, model);
            Toasts.error(model, e.getMessage());
            return INDEX;
        }
    }

    @PostMapping("/academy/exam")
    public String submitExam(@RequestParam Map<String, String> allParams, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        try {
            Map<UUID, Integer> answers = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : allParams.entrySet()) {
                if (e.getKey().startsWith("q_") && !e.getValue().isBlank()) {
                    answers.put(UUID.fromString(e.getKey().substring(2)), Integer.parseInt(e.getValue()));
                }
            }
            var result = academyService.submitExam(clinicId(session), actor(session), answers);
            activityLogService.log(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    "academy.submitExam", "academy_exam_attempt");
            model.addAttribute("result", result);
            if (result.passed()) {
                model.addAttribute("certificate", academyService.certificate(clinicId(session), actor(session)));
                return CERT;
            }
            renderIndex(session, model);
            Toasts.error(model, "لم تنجح — حاول مرة أخرى");
            return INDEX;
        } catch (IllegalArgumentException e) {
            renderIndex(session, model);
            Toasts.error(model, e.getMessage());
            return INDEX;
        }
    }

    @GetMapping("/academy/certificate")
    public String certificate(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        try {
            model.addAttribute("certificate", academyService.certificate(clinicId(session), actor(session)));
            return CERT;
        } catch (IllegalArgumentException e) {
            renderIndex(session, model);
            Toasts.error(model, e.getMessage());
            return INDEX;
        }
    }

    @GetMapping("/academy/verify")
    public String verifyQueue(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        renderQueue(session, model);
        return QUEUE;
    }

    @PostMapping("/academy/submissions/{id}/verify")
    public String verify(@PathVariable UUID id, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            academyService.verify(clinicId(session), actor(session), id);
            activityLogService.log(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    "academy.verify", "academy_step_submission");
        } catch (IllegalArgumentException e) {
            Toasts.error(model, e.getMessage());
        }
        renderQueue(session, model);
        return QUEUE;
    }

    @PostMapping("/academy/submissions/{id}/reject")
    public String reject(@PathVariable UUID id, @RequestParam String reason, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            academyService.reject(clinicId(session), actor(session), id, reason);
            activityLogService.log(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    "academy.reject", "academy_step_submission");
        } catch (IllegalArgumentException e) {
            Toasts.error(model, e.getMessage());
        }
        renderQueue(session, model);
        return QUEUE;
    }

    @GetMapping("/academy/curriculum")
    public String curriculum(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        renderCurriculum(session, model);
        return CURRICULUM;
    }

    @PostMapping("/academy/curriculum/import")
    public String importDefault(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            academyService.importDefaultCurriculum(clinicId(session), actor(session));
            activityLogService.log(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    "academy.importCurriculum", "academy_unit");
            Toasts.success(model, "تم استيراد المنهج الافتراضي");
        } catch (IllegalArgumentException e) {
            Toasts.error(model, e.getMessage());
        }
        renderCurriculum(session, model);
        return CURRICULUM;
    }

    @GetMapping("/academy/units/{unitId}/edit")
    public String editUnit(@PathVariable UUID unitId, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        try {
            model.addAttribute("form", UnitForm.from(academyService.unit(clinicId(session), unitId)));
            return UNIT_EDITOR;
        } catch (IllegalArgumentException e) {
            renderCurriculum(session, model);
            Toasts.error(model, e.getMessage());
            return CURRICULUM;
        }
    }

    @GetMapping("/academy/units/new")
    public String newUnit(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        model.addAttribute("form", new UnitForm());
        return UNIT_EDITOR;
    }

    @PostMapping("/academy/units")
    public String saveUnit(@ModelAttribute("form") UnitForm form, HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            Unit saved = academyService.saveUnit(clinicId(session), actor(session), form.toRequest());
            activityLogService.log(clinicId(session), (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                    "academy.saveUnit", "academy_unit");
            return "redirect:/academy/units/" + saved.id() + "/edit";
        } catch (IllegalArgumentException e) {
            renderCurriculum(session, model);
            Toasts.error(model, e.getMessage());
            return CURRICULUM;
        }
    }

    private void renderIndex(HttpSession session, Model model) {
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        List<Employee> employees = employeeService.list(clinicId(session));
        model.addAttribute("employees", employees);
        model.addAttribute("audiences",
                employees.stream().collect(java.util.stream.Collectors.toMap(Employee::id,
                        emp -> academyService.audienceOf(clinicId(session), emp.id()))));
        model.addAttribute("canVerify", canVerify(session));
    }

    private void renderQueue(HttpSession session, Model model) {
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        List<Submission> pending = academyService.pendingSubmissions(clinicId(session));
        model.addAttribute("pending", pending);
    }

    private void renderCurriculum(HttpSession session, Model model) {
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        model.addAttribute("units", academyService.curriculum(clinicId(session)));
        model.addAttribute("canEdit", canEdit(session));
    }

    private String redirectToMe(Model model, HttpSession session) {
        model.addAttribute("layout", layoutModel.forRequest(session, "academy"));
        return "redirect:/academy/me";
    }

    private Actor actor(HttpSession session) {
        Employee employee = employeeService.findByMembership(clinicId(session),
                (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID));
        return new Actor((UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID),
                (String) session.getAttribute(SessionKeys.ROLE_CODE),
                employee == null ? null : employee.id());
    }

    private UUID employeeId(HttpSession session) {
        Employee employee = employeeService.findByMembership(clinicId(session),
                (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID));
        return employee == null ? null : employee.id();
    }

    private String employeeName(HttpSession session) {
        Employee employee = employeeService.findByMembership(clinicId(session),
                (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID));
        return employee == null ? "" : employee.name();
    }

    private static boolean hasSession(HttpSession session) {
        return session.getAttribute(SessionKeys.CLINIC_ID) instanceof UUID
                && session.getAttribute(SessionKeys.MEMBERSHIP_ID) instanceof UUID
                && session.getAttribute(SessionKeys.ROLE_CODE) instanceof String;
    }

    private static UUID clinicId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.CLINIC_ID);
    }

    private static boolean canVerify(HttpSession session) {
        return canAny(session, "owner", "manager");
    }

    private static boolean canEdit(HttpSession session) {
        return canAny(session, "owner", "manager");
    }

    private static boolean canAny(HttpSession session, String... roles) {
        String role = (String) session.getAttribute(SessionKeys.ROLE_CODE);
        for (String r : roles) {
            if (r.equals(role)) {
                return true;
            }
        }
        return false;
    }

    public static class UnitForm {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
        private static final com.fasterxml.jackson.core.type.TypeReference<List<AcademyService.Section>> SECTIONS_TYPE = new com.fasterxml.jackson.core.type.TypeReference<>() {};
        private static final com.fasterxml.jackson.core.type.TypeReference<List<AcademyService.QuestionRequest>> QUESTIONS_TYPE = new com.fasterxml.jackson.core.type.TypeReference<>() {};

        private UUID id;
        private String appliesTo = "core";
        private String icon;
        private String title;
        private String goal;
        private String sectionsJson = "[]";
        private String questionsJson = "[]";
        private String photoTask;
        private boolean requiresPhoto;

        static UnitForm from(Unit unit) {
            UnitForm f = new UnitForm();
            f.id = unit.id();
            f.appliesTo = unit.appliesTo().getLiteral();
            f.icon = unit.icon();
            f.title = unit.title();
            f.goal = unit.goal();
            try {
                f.sectionsJson = JSON.writeValueAsString(unit.content());
            } catch (Exception ignored) {
            }
            f.photoTask = unit.photoTask();
            f.requiresPhoto = unit.requiresPhoto();
            return f;
        }

        AcademyService.UnitRequest toRequest() {
            List<AcademyService.Section> sections = parseJson(sectionsJson, SECTIONS_TYPE);
            List<AcademyService.QuestionRequest> questions = parseJson(questionsJson, QUESTIONS_TYPE);
            return new AcademyService.UnitRequest(id,
                    AcademyAudience.valueOf(appliesTo), icon, title, goal,
                    sections, photoTask, requiresPhoto, questions);
        }

        private <T> List<T> parseJson(String json, com.fasterxml.jackson.core.type.TypeReference<List<T>> ref) {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            try {
                return JSON.readValue(json, ref);
            } catch (Exception e) {
                return List.of();
            }
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getAppliesTo() { return appliesTo; }
        public void setAppliesTo(String appliesTo) { this.appliesTo = appliesTo; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getGoal() { return goal; }
        public void setGoal(String goal) { this.goal = goal; }
        public String getSectionsJson() { return sectionsJson; }
        public void setSectionsJson(String sectionsJson) { this.sectionsJson = sectionsJson; }
        public String getQuestionsJson() { return questionsJson; }
        public void setQuestionsJson(String questionsJson) { this.questionsJson = questionsJson; }
        public String getPhotoTask() { return photoTask; }
        public void setPhotoTask(String photoTask) { this.photoTask = photoTask; }
        public boolean isRequiresPhoto() { return requiresPhoto; }
        public void setRequiresPhoto(boolean requiresPhoto) { this.requiresPhoto = requiresPhoto; }
    }
}