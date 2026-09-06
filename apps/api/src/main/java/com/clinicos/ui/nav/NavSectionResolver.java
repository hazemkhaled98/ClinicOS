package com.clinicos.ui.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Maps a membership's effective permission codes to the drawer sections it may
 * open, following the legacy panel's rules:
 *
 * <ul>
 *   <li>{@code emp} shows employee management, and also "my evaluation" unless the
 *       member is an owner (owners manage everyone, not just themselves)</li>
 *   <li>{@code quick} shows quick access and {@code ceo} shows the dashboard</li>
 *   <li>tasks needs {@code tasksTab} but is superseded by the dashboard
 *       ({@code ceo})</li>
 *   <li>the prep section is unconditional (no permission gates it)</li>
 *   <li>the academy needs either {@code acadVerify} or {@code acadEdit}</li>
 *   <li>inventory appears when any inventory-area code is present</li>
 * </ul>
 */
public final class NavSectionResolver {

    private static final Set<String> INVENTORY_CODES = Set.of(
            "tray", "issue", "procs", "myprocs", "manage", "orders", "receive", "returns",
            "suppliers", "dash", "profit", "analytics", "waste", "doctors", "supAnalysis",
            "received", "itemAnalysis", "approvals", "ledger");

    private static final List<NavSection> ALL = List.of(
            new NavSection("emp", "employees", "تسجيل الموظف", "تسجيل الموظف", "إدارة ملفات الموظفين وصلاحياتهم", NavIcon.USER),
            new NavSection("myeval", "my-evaluation", "تقييمي", "تقييمي", "تقييم الأداء الشخصي", NavIcon.EDIT),
            new NavSection("quick", "quick-access", "الوصول السريع", "الوصول السريع", "أدوات سريعة للمهام اليومية", NavIcon.BOLT),
            new NavSection("tasks", "tasks", "المهام", "المهام", "إدارة المهام اليومية", NavIcon.TASKS),
            new NavSection("prep", "prep", "تحضير الجلسات", "تحضير الجلسات", "إعداد قوائم الإجراءات الطبية", NavIcon.CLIPBOARD_CHECK),
            new NavSection("acad", "academy", "الأكاديمية", "الأكاديمية", "البرامج التدريبية الأكاديمية", NavIcon.STAR),
            new NavSection("inv", "inventory", "المخزن", "المخزن", "إدارة مخزون العيادة وتكاليف الإجراءات", NavIcon.ARCHIVE),
            new NavSection("ceo", "admin-dashboard", "لوحة التحكم", "لوحة التحكم", "التحليلات التشغيلية والإدارية", NavIcon.CHART));

    private NavSectionResolver() {
    }

    public static List<NavSection> resolve(Set<String> permissionCodes, String roleCode) {
        boolean owner = "owner".equals(roleCode);
        List<NavSection> visible = new ArrayList<>();
        for (NavSection section : ALL) {
            switch (section.key()) {
                case "emp" -> {
                    if (permissionCodes.contains("emp")) {
                        visible.add(section);
                    }
                }
                case "myeval" -> {
                    if (!owner && permissionCodes.contains("emp")) {
                        visible.add(section);
                    }
                }
                case "quick" -> {
                    if (permissionCodes.contains("quick")) {
                        visible.add(section);
                    }
                }
                case "tasks" -> {
                    if (permissionCodes.contains("tasksTab") && !permissionCodes.contains("ceo")) {
                        visible.add(section);
                    }
                }
                case "prep" -> visible.add(section);
                case "acad" -> {
                    if (permissionCodes.contains("acadVerify") || permissionCodes.contains("acadEdit")) {
                        visible.add(section);
                    }
                }
                case "inv" -> {
                    if (!Collections.disjoint(permissionCodes, INVENTORY_CODES)) {
                        visible.add(section);
                    }
                }
                case "ceo" -> {
                    if (permissionCodes.contains("ceo")) {
                        visible.add(section);
                    }
                }
            }
        }
        return visible;
    }

    public static NavSection sectionByRoute(String route) {
        for (NavSection section : ALL) {
            if (section.route().equals(route)) {
                return section;
            }
        }
        return null;
    }

    public static List<NavSection> all() {
        return ALL;
    }
}