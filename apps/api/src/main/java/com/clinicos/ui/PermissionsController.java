package com.clinicos.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.clinicos.identity.api.RolePermissionService;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Controller
public class PermissionsController {

    private static final Logger log = LoggerFactory.getLogger(PermissionsController.class);

    private static final List<String> ROLE_CODES = List.of("owner", "manager", "assistant", "receptionist");

    private static final Map<String, String> PERMISSION_LABELS = Map.ofEntries(
            Map.entry("emp", "الموظفين"),
            Map.entry("quick", "الوصول السريع"),
            Map.entry("ceo", "لوحة التحكم"),
            Map.entry("tasksTab", "تبويب المهام"),
            Map.entry("acadVerify", "التحقق الأكاديمي"),
            Map.entry("acadEdit", "تعديل الأكاديمي"),
            Map.entry("tray", "الصينية"),
            Map.entry("issue", "الإصدار"),
            Map.entry("procs", "الإجراءات"),
            Map.entry("myprocs", "إجراءاتي"),
            Map.entry("manage", "الإدارة"),
            Map.entry("orders", "الطلبات"),
            Map.entry("receive", "الاستلام"),
            Map.entry("returns", "المرتجعات"),
            Map.entry("suppliers", "الموردون"),
            Map.entry("dash", "لوحة القيادة"),
            Map.entry("profit", "الربحية"),
            Map.entry("analytics", "التحليلات"),
            Map.entry("waste", "الهدر"),
            Map.entry("doctors", "الأطباء"),
            Map.entry("supAnalysis", "تحليل الموردين"),
            Map.entry("received", "المستلم"),
            Map.entry("itemAnalysis", "تحليل الأصناف"),
            Map.entry("approvals", "الاعتمادات"),
            Map.entry("ledger", "دفتر الأستاذ"));

    private final LayoutModel layoutModel;
    private final RolePermissionService rolePermissionService;
    private final UserAdminService userAdminService;
    private final ActivityLogService activityLogService;

    public PermissionsController(LayoutModel layoutModel, RolePermissionService rolePermissionService,
            UserAdminService userAdminService, ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.rolePermissionService = rolePermissionService;
        this.userAdminService = userAdminService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin-dashboard/permissions")
    public String permissions(HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        renderPage(model, session);
        return "admin/permissions-page";
    }

    @PostMapping("/admin-dashboard/permissions")
    public String updatePermissions(@Valid PermissionsForm form, BindingResult binding,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        if (fieldErrors.isEmpty()) {
            Set<String> codes = form.getPermissionCodes() != null ? Set.of(form.getPermissionCodes()) : Set.of();
            try {
                rolePermissionService.setPermissions(clinicId, AdminAccess.membershipId(session), form.getRoleCode(), codes);
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "permissions.update", "role_permission");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("permissions", e.getMessage());
            }
        }
        renderPage(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم حفظ الصلاحيات");
        return "admin/permissions :: permissionsCard";
    }

    private void renderPage(Model model, HttpSession session) {
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("roleCodes", manageableRoleCodes(AdminAccess.roleCode(session)));
        model.addAttribute("roleNames", roleNames());
        model.addAttribute("permissionLabels", PERMISSION_LABELS);
        model.addAttribute("usersByRole", usersByRole(userAdminService.list(clinicId)));
        try {
            model.addAttribute("rolePermissionMap", toMap(rolePermissionService.listForClinic(clinicId)));
        } catch (Exception e) {
            log.warn("listForClinic failed: clinic {}", clinicId, e);
            model.addAttribute("rolePermissionMap", Map.of());
        }
    }

    private static List<String> manageableRoleCodes(String actorRole) {
        if ("owner".equals(actorRole)) {
            return ROLE_CODES;
        }
        return ROLE_CODES.stream()
                .filter(code -> !"owner".equals(code) && !"manager".equals(code))
                .toList();
    }

    private static Map<String, String> roleNames() {
        return ROLE_CODES.stream().collect(Collectors.toMap(code -> code, LayoutModel::roleDisplayName));
    }

    private static Map<String, List<UserSummary>> usersByRole(List<UserSummary> users) {
        return users.stream().collect(Collectors.groupingBy(UserSummary::roleCode));
    }

    private static Map<String, Set<String>> toMap(
            java.util.List<RolePermissionService.RolePermissionRow> rows) {
        return rows.stream().collect(
                Collectors.groupingBy(RolePermissionService.RolePermissionRow::roleCode,
                        Collectors.mapping(RolePermissionService.RolePermissionRow::permissionCode,
                                Collectors.toSet())));
    }

    public static class PermissionsForm {
        @NotBlank(message = "الدور مطلوب")
        private String roleCode;
        private String[] permissionCodes;

        static PermissionsForm of(String roleCode, String... permissionCodes) {
            PermissionsForm form = new PermissionsForm();
            form.roleCode = roleCode;
            form.permissionCodes = permissionCodes;
            return form;
        }

        public String getRoleCode() {
            return roleCode;
        }

        public void setRoleCode(String roleCode) {
            this.roleCode = roleCode;
        }

        public String[] getPermissionCodes() {
            return permissionCodes;
        }

        public void setPermissionCodes(String[] permissionCodes) {
            this.permissionCodes = permissionCodes;
        }
    }
}