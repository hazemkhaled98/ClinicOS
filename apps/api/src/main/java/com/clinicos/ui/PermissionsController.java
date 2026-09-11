package com.clinicos.ui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.identity.api.RolePermissionService;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;

import jakarta.servlet.http.HttpSession;

@Controller
public class PermissionsController {

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
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        model.addAttribute("rolePermissionMap", toMap(rolePermissionService.listForClinic(clinicId)));
        model.addAttribute("permissionLabels", PERMISSION_LABELS);
        model.addAttribute("permissionError", (String) null);
        model.addAttribute("usersByRole", usersByRole(userAdminService.list(clinicId)));
        return "admin/permissions-page";
    }

    @PostMapping("/admin-dashboard/permissions")
    public String updatePermissions(@RequestParam String roleCode,
            @RequestParam(required = false) String[] permissionCodes,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Set<String> codes = permissionCodes != null ? Set.of(permissionCodes) : Set.of();
        String error = null;
        try {
            rolePermissionService.setPermissions(clinicId, roleCode, codes);
            activityLogService.log(clinicId, AdminAccess.membershipId(session), "permissions.update", "role_permission");
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        model.addAttribute("permissionError", error);
        try {
            model.addAttribute("rolePermissionMap", toMap(rolePermissionService.listForClinic(clinicId)));
        } catch (Exception e) {
            model.addAttribute("rolePermissionMap", Map.of());
        }
        model.addAttribute("permissionLabels", PERMISSION_LABELS);
        model.addAttribute("usersByRole", usersByRole(userAdminService.list(clinicId)));
        if (error == null) {
            Toasts.success(model, "تم حفظ الصلاحيات");
        } else {
            Toasts.error(model, error);
        }
        return "admin/permissions :: permissionsCard";
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
}
