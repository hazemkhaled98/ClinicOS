package com.clinicos.ui;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.LeaveRequestService;

import jakarta.servlet.http.HttpSession;

@Controller
public class LeaveController {

    private static final String MINE = "leaves-me";
    private static final String QUEUE = "leaves-queue";

    private final LayoutModel layoutModel;
    private final LeaveRequestService leaveRequests;
    private final EmployeeService employeeService;

    public LeaveController(LayoutModel layoutModel, LeaveRequestService leaveRequests,
            EmployeeService employeeService) {
        this.layoutModel = layoutModel;
        this.leaveRequests = leaveRequests;
        this.employeeService = employeeService;
    }

    @GetMapping("/leaves/me")
    public String mine(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        renderMine(session, model, new LinkedHashMap<>(), null, null, null);
        return MINE;
    }

    @GetMapping("/leaves")
    public String queue(HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        if (!canApprove(session)) {
            return "redirect:/leaves/me";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "leaves/me"));
        model.addAttribute("pending", leaveRequests.listPendingForApprover(clinicId(session), membershipId(session),
                roleCode(session)));
        return QUEUE;
    }

    @PostMapping("/leaves")
    public String submit(@RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String reason,
            HttpSession session, Model model, RedirectAttributes redirect) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate startDate = parseDate(start, "start", errors);
        LocalDate endDate = parseDate(end, "end", errors);
        if (reason == null || reason.isBlank()) {
            errors.put("reason", "اكتب سبب الطلب");
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            errors.put("end", "تاريخ النهاية قبل تاريخ البداية");
        }
        UUID employeeId = ownEmployeeId(session);
        if (errors.isEmpty() && employeeId == null) {
            errors.put("start", "لا يوجد ملف موظف مرتبط بحسابك");
        }
        if (!errors.isEmpty()) {
            renderMine(session, model, errors, start, end, reason);
            return MINE;
        }
        try {
            leaveRequests.submit(clinicId(session), employeeId, startDate, endDate, reason.trim(), membershipId(session));
            flashSuccess(redirect, "تم إرسال طلب الإجازة");
        } catch (IllegalArgumentException exception) {
            model.addAttribute("formError", exception.getMessage());
            renderMine(session, model, errors, start, end, reason);
            return MINE;
        }
        return "redirect:/leaves/me";
    }

    @PostMapping("/leaves/{id}/approve")
    public String approve(@PathVariable UUID id, HttpSession session, RedirectAttributes redirect) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        decide(redirect, () -> leaveRequests.approve(clinicId(session), id, membershipId(session)), "تم اعتماد الإجازة");
        return "redirect:/leaves";
    }

    @PostMapping("/leaves/{id}/reject")
    public String reject(@PathVariable UUID id,
            @RequestParam(required = false) String note,
            HttpSession session, RedirectAttributes redirect) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        if (note == null || note.isBlank()) {
            flashError(redirect, "اكتب سبب الرفض");
            return "redirect:/leaves";
        }
        decide(redirect, () -> leaveRequests.reject(clinicId(session), id, note.trim(), membershipId(session)), "تم رفض الإجازة");
        return "redirect:/leaves";
    }

    @PostMapping("/leaves/{id}/cancel")
    public String cancel(@PathVariable UUID id, HttpSession session, RedirectAttributes redirect) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        try {
            leaveRequests.cancel(clinicId(session), id, ownEmployeeId(session), membershipId(session));
            flashSuccess(redirect, "تم إلغاء الطلب");
        } catch (IllegalArgumentException exception) {
            flashError(redirect, exception.getMessage());
        }
        return "redirect:/leaves/me";
    }

    private void decide(RedirectAttributes redirect, Runnable action, String successMessage) {
        try {
            action.run();
            flashSuccess(redirect, successMessage);
        } catch (IllegalArgumentException exception) {
            flashError(redirect, exception.getMessage());
        }
    }

    private void renderMine(HttpSession session, Model model, Map<String, String> errors,
            String start, String end, String reason) {
        model.addAttribute("layout", layoutModel.forRequest(session, "leaves/me"));
        model.addAttribute("errors", errors);
        model.addAttribute("start", start);
        model.addAttribute("end", end);
        model.addAttribute("reason", reason);
        model.addAttribute("canApprove", canApprove(session));
        model.addAttribute("pendingCount",
                canApprove(session) ? leaveRequests.listPendingForApprover(clinicId(session), membershipId(session),
                        roleCode(session)).size() : 0);
        UUID employeeId = ownEmployeeId(session);
        boolean soleOwner = "owner".equals(roleCode(session))
                && !leaveRequests.hasOtherActiveOwner(clinicId(session), membershipId(session));
        model.addAttribute("hasEmployee", employeeId != null);
        model.addAttribute("soleOwner", soleOwner);
        model.addAttribute("canRequest", employeeId != null && !soleOwner);
        model.addAttribute("requests", employeeId == null ? List.of() : leaveRequests.listForEmployee(clinicId(session), employeeId));
    }

    private UUID ownEmployeeId(HttpSession session) {
        var employee = employeeService.findByMembership(clinicId(session), membershipId(session));
        return employee == null ? null : employee.id();
    }

    private static LocalDate parseDate(String value, String key, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            errors.put(key, "اختر التاريخ");
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            errors.put(key, "تاريخ غير صحيح");
            return null;
        }
    }

    private static void flashSuccess(RedirectAttributes redirect, String message) {
        redirect.addFlashAttribute("toastMessage", message);
        redirect.addFlashAttribute("toastType", "success");
    }

    private static void flashError(RedirectAttributes redirect, String message) {
        redirect.addFlashAttribute("toastMessage", message);
        redirect.addFlashAttribute("toastType", "error");
    }

    private static boolean hasSession(HttpSession session) {
        return session.getAttribute(SessionKeys.CLINIC_ID) instanceof UUID
                && session.getAttribute(SessionKeys.MEMBERSHIP_ID) instanceof UUID
                && session.getAttribute(SessionKeys.ROLE_CODE) instanceof String;
    }

    private static boolean canApprove(HttpSession session) {
        String role = roleCode(session);
        return "owner".equals(role) || "manager".equals(role);
    }

    private static UUID clinicId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.CLINIC_ID);
    }

    private static UUID membershipId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID);
    }

    private static String roleCode(HttpSession session) {
        return (String) session.getAttribute(SessionKeys.ROLE_CODE);
    }
}
