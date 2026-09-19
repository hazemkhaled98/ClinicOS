package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.procedures.ProceduresService;
import com.clinicos.procedures.ProceduresService.BomLineRequest;
import com.clinicos.procedures.ProceduresService.CaseDraft;
import com.clinicos.procedures.ProceduresService.CaseItemRequest;
import com.clinicos.procedures.ProceduresService.ProcedureRequest;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.staff.api.EmployeeService;

import jakarta.servlet.http.HttpSession;

@Controller
public class ProceduresController {

    private static final String AREA = "inventory";
    private final LayoutModel layoutModel;
    private final ProceduresService proceduresService;
    private final ActivityLogService activityLogService;
    private final EmployeeService employeeService;

    public ProceduresController(LayoutModel layoutModel, ProceduresService proceduresService,
            ActivityLogService activityLogService, EmployeeService employeeService) {
        this.layoutModel = layoutModel;
        this.proceduresService = proceduresService;
        this.activityLogService = activityLogService;
        this.employeeService = employeeService;
    }

    @GetMapping("/inventory/procs")
    public String procedures(@RequestParam(defaultValue = "false") boolean includeArchived,
            HttpSession session, Model model) {
        if (!allowed(session, "procs")) return "redirect:/inventory";
        renderProcedures(session, model, includeArchived);
        return "procedures";
    }

    @PostMapping("/inventory/procs")
    public String create(@ModelAttribute ProcedureForm form, HttpSession session,
            RedirectAttributes redirect) {
        if (!allowed(session, "procs")) return "redirect:/inventory";
        try {
            proceduresService.createProcedure(clinicId(session), actor(session), form.request());
            log(session, "inventory.procedure.create", "procedure");
            redirect.addFlashAttribute("toastMessage", "تم حفظ الإجراء");
            redirect.addFlashAttribute("toastType", "success");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("toastMessage", exception.getMessage());
            redirect.addFlashAttribute("toastType", "error");
        }
        return "redirect:/inventory/procs";
    }

    @PostMapping("/inventory/procs/{id}/change")
    public String change(@PathVariable UUID id, @RequestParam String kind,
            @ModelAttribute ProcedureForm form, HttpSession session, RedirectAttributes redirect) {
        if (!allowed(session, "procs")) return "redirect:/inventory";
        try {
            proceduresService.requestProcedureChange(clinicId(session), actor(session), id,
                    changeKind(kind), form.request());
            log(session, "inventory.procedure.request-change", "inventory_change_request");
            redirect.addFlashAttribute("toastMessage", "بانتظار موافقة المدير");
            redirect.addFlashAttribute("toastType", "success");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("toastMessage", exception.getMessage());
            redirect.addFlashAttribute("toastType", "error");
        }
        return "redirect:/inventory/procs";
    }

    @PostMapping("/inventory/procs/{id}/bom-change")
    public String bomChange(@PathVariable UUID id, @RequestParam List<UUID> itemId,
            @RequestParam List<BigDecimal> qty, HttpSession session, RedirectAttributes redirect) {
        if (!allowed(session, "procs")) return "redirect:/inventory";
        try {
            proceduresService.requestProcedureBomChange(clinicId(session), actor(session), id, bomLines(itemId, qty));
            log(session, "inventory.procedure.request-bom-change", "inventory_change_request");
            redirect.addFlashAttribute("toastMessage", "بانتظار موافقة المدير");
            redirect.addFlashAttribute("toastType", "success");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("toastMessage", exception.getMessage());
            redirect.addFlashAttribute("toastType", "error");
        }
        return "redirect:/inventory/procs";
    }

    @GetMapping("/inventory/myprocs")
    public String myProcedures(@RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpSession session, Model model) {
        if (!allowed(session, "myprocs")) return "redirect:/inventory";
        var employee = employeeService.findByMembership(clinicId(session), AdminAccess.membershipId(session));
        if (employee == null) return "redirect:/inventory";
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("procedures", proceduresService.procedures(clinicId(session), false));
        model.addAttribute("cases", proceduresService.cases(clinicId(session), employee.id(), from, to));
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "my-procedures";
    }

    @PostMapping("/inventory/myprocs")
    public String record(@ModelAttribute CaseForm form, HttpSession session, RedirectAttributes redirect) {
        if (!allowed(session, "myprocs")) return "redirect:/inventory";
        try {
            var employee = employeeService.findByMembership(clinicId(session), AdminAccess.membershipId(session));
            if (employee == null) throw new IllegalArgumentException("المستخدم غير مرتبط بموظف");
            proceduresService.recordCase(clinicId(session), actor(session), form.draft(employee.id()));
            log(session, "inventory.procedure-case.create", "procedure_case");
            redirect.addFlashAttribute("toastMessage", "تم تسجيل الإجراء");
            redirect.addFlashAttribute("toastType", "success");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("toastMessage", exception.getMessage());
            redirect.addFlashAttribute("toastType", "error");
        }
        return "redirect:/inventory/myprocs";
    }

    private void renderProcedures(HttpSession session, Model model, boolean includeArchived) {
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("procedures", proceduresService.procedures(clinicId(session), includeArchived));
        model.addAttribute("includeArchived", includeArchived);
    }

    private static List<BomLineRequest> bomLines(List<UUID> itemIds, List<BigDecimal> quantities) {
        if (itemIds == null || quantities == null || itemIds.size() != quantities.size()) {
            throw new IllegalArgumentException("بيانات الوصفة غير صالحة");
        }
        return java.util.stream.IntStream.range(0, itemIds.size())
                .mapToObj(i -> new BomLineRequest(itemIds.get(i), quantities.get(i))).toList();
    }

    private static ChangeRequestKind changeKind(String kind) {
        return switch (kind) {
            case "edit" -> ChangeRequestKind.edit;
            case "delete" -> ChangeRequestKind.delete;
            default -> throw new IllegalArgumentException("نوع الطلب غير صالح");
        };
    }

    private boolean allowed(HttpSession session, String code) {
        return session.getAttribute(SessionKeys.CLINIC_ID) instanceof UUID
                && session.getAttribute(SessionKeys.MEMBERSHIP_ID) instanceof UUID
                && session.getAttribute(SessionKeys.ROLE_CODE) instanceof String
                && AdminAccess.hasCode(session, code);
    }

    private Actor actor(HttpSession session) {
        return new Actor(AdminAccess.membershipId(session), AdminAccess.roleCode(session));
    }

    private UUID clinicId(HttpSession session) {
        return AdminAccess.clinicId(session);
    }

    private void log(HttpSession session, String action, String entity) {
        activityLogService.log(clinicId(session), AdminAccess.membershipId(session), action, entity);
    }

    public static class ProcedureForm {
        private String name;
        private BigDecimal price;
        private BigDecimal laborCost;
        private BigDecimal doctorFee;
        private List<UUID> itemId = List.of();
        private List<BigDecimal> qty = List.of();

        ProcedureRequest request() {
            return new ProcedureRequest(name, price, laborCost, doctorFee, bomLines(itemId, qty));
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public BigDecimal getLaborCost() { return laborCost; }
        public void setLaborCost(BigDecimal laborCost) { this.laborCost = laborCost; }
        public BigDecimal getDoctorFee() { return doctorFee; }
        public void setDoctorFee(BigDecimal doctorFee) { this.doctorFee = doctorFee; }
        public List<UUID> getItemId() { return itemId; }
        public void setItemId(List<UUID> itemId) { this.itemId = itemId; }
        public List<BigDecimal> getQty() { return qty; }
        public void setQty(List<BigDecimal> qty) { this.qty = qty; }
    }

    public static class CaseForm {
        private UUID procedureId;
        private UUID employeeId;
        private String doctorName;
        private String patientRef;
        private List<UUID> itemId = List.of();
        private List<BigDecimal> qty = List.of();

        CaseDraft draft(UUID employeeId) {
            return new CaseDraft(procedureId, employeeId, doctorName, patientRef, bomLines(itemId, qty).stream()
                    .map(line -> new CaseItemRequest(line.itemId(), line.qty())).toList());
        }

        public UUID getProcedureId() { return procedureId; }
        public void setProcedureId(UUID procedureId) { this.procedureId = procedureId; }
        public UUID getEmployeeId() { return employeeId; }
        public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
        public String getDoctorName() { return doctorName; }
        public void setDoctorName(String doctorName) { this.doctorName = doctorName; }
        public String getPatientRef() { return patientRef; }
        public void setPatientRef(String patientRef) { this.patientRef = patientRef; }
        public List<UUID> getItemId() { return itemId; }
        public void setItemId(List<UUID> itemId) { this.itemId = itemId; }
        public List<BigDecimal> getQty() { return qty; }
        public void setQty(List<BigDecimal> qty) { this.qty = qty; }
    }
}
