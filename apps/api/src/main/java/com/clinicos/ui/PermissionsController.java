package com.clinicos.ui;

import java.util.HashMap;
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
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;

import jakarta.servlet.http.HttpSession;

@Controller
public class PermissionsController {

    private final LayoutModel layoutModel;
    private final RolePermissionService rolePermissionService;
    private final ActivityLogService activityLogService;

    public PermissionsController(LayoutModel layoutModel, RolePermissionService rolePermissionService,
            ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.rolePermissionService = rolePermissionService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin-dashboard/permissions")
    public String permissions(HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = clinicId(session);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        model.addAttribute("rolePermissionMap", toMap(rolePermissionService.listForClinic(clinicId)));
        return "admin/permissions-page";
    }

    @PostMapping("/admin-dashboard/permissions")
    public String updatePermissions(@RequestParam String roleCode,
            @RequestParam(required = false) String[] permissionCodes,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = clinicId(session);
        Set<String> codes = permissionCodes != null ? Set.of(permissionCodes) : Set.of();
        rolePermissionService.setPermissions(clinicId, roleCode, codes);
        activityLogService.log(clinicId, membershipId(session), "permissions.update", "role_permission");
        model.addAttribute("rolePermissionMap", toMap(rolePermissionService.listForClinic(clinicId)));
        return "admin/permissions :: permissionsCard";
    }

    private static Map<String, Set<String>> toMap(
            java.util.List<RolePermissionService.RolePermissionRow> rows) {
        return rows.stream().collect(
                Collectors.groupingBy(RolePermissionService.RolePermissionRow::roleCode,
                        Collectors.mapping(RolePermissionService.RolePermissionRow::permissionCode,
                                Collectors.toSet())));
    }

    private static UUID clinicId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.CLINIC_ID);
    }

    private static UUID membershipId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.MEMBERSHIP_ID);
    }
}
