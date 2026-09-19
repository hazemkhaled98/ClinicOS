package com.clinicos.ui;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.InventoryService.ItemRequest;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.shared.jooq.enums.LocationKind;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Controller
public class InventoryController {

    private static final String AREA = "inventory";
    private static final List<SubArea> AREAS = List.of(
            new SubArea("items", "manage", "الأصناف"),
            new SubArea("tray", "tray", "صينية التحضير"),
            new SubArea("issue", "issue", "صرف المخزون"),
            new SubArea("ledger", "ledger", "سجل الحركة"),
            new SubArea("approvals", "approvals", "طلبات الموافقة"));

    private final LayoutModel layoutModel;
    private final InventoryService inventoryService;
    private final ActivityLogService activityLogService;

    public InventoryController(LayoutModel layoutModel, InventoryService inventoryService,
            ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.inventoryService = inventoryService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/inventory")
    public String index(HttpServletRequest request, HttpServletResponse response,
            HttpSession session, Model model) {
        if (!hasSession(session)) {
            return "redirect:/login";
        }
        List<SubArea> permitted = AREAS.stream()
                .filter(a -> AdminAccess.hasCode(session, a.code))
                .toList();
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("areas", permitted);
        writeLastSectionCookie(response, AREA);
        return AREA;
    }

    @GetMapping("/inventory/items")
    public String items(@RequestParam(defaultValue = "false") boolean includeArchived,
            HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "manage")) {
            return "redirect:/inventory";
        }
        renderItems(session, model, includeArchived);
        return "inventory-items";
    }

    @PostMapping("/inventory/items")
    public String createItem(@ModelAttribute ItemForm form, HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "manage")) {
            return "redirect:/inventory";
        }
        try {
            inventoryService.createItem(clinicId(session), actor(session), form.toRequest());
            log(session, "inventory.item.create", "inventory_item");
            return "redirect:/inventory/items";
        } catch (IllegalArgumentException exception) {
            renderItems(session, model, false);
            Toasts.error(model, exception.getMessage());
            return "inventory-items";
        }
    }

    @PostMapping("/inventory/items/{id}/change")
    public String requestChange(@PathVariable UUID id, @RequestParam String kind,
            @ModelAttribute ItemForm form, HttpSession session, RedirectAttributes redirect) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "manage")) {
            return "redirect:/inventory";
        }
        inventoryService.requestItemChange(clinicId(session), actor(session), id,
                changeKind(kind), form.toRequest());
        log(session, "inventory.item.request-change", "inventory_change_request");
        redirect.addFlashAttribute("toastMessage", "بانتظار موافقة المدير");
        redirect.addFlashAttribute("toastType", "success");
        return "redirect:/inventory/items";
    }

    @GetMapping("/inventory/tray")
    public String tray(HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "tray")) {
            return "redirect:/inventory";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("stock", inventoryService.stock(clinicId(session), LocationKind.tray));
        return "inventory-tray";
    }

    @GetMapping("/inventory/issue")
    public String issue(HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "issue")) {
            return "redirect:/inventory";
        }
        renderStock(session, model);
        return "inventory-issue";
    }

    @PostMapping("/inventory/issue")
    public String issueExec(@RequestParam UUID itemId, @RequestParam BigDecimal requestedQty,
            HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "issue")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "غير مصرح");
        }
        try {
            InventoryService.IssueResult result = inventoryService.issue(clinicId(session),
                    actor(session), itemId, LocationKind.store, requestedQty);
            if (result.issuedQty() != null && result.issuedQty().signum() > 0) {
                log(session, "inventory.issue", "stock_movement");
            }
            if (result.clamped()) {
                Toasts.success(model, "تم صرف الكمية المتاحة فقط: " + result.issuedQty());
            } else if (result.issuedQty() == null || result.issuedQty().signum() <= 0) {
                Toasts.error(model, "لا يوجد رصيد متاح لهذا الصنف");
            }
            renderStock(session, model);
            return "inventory-issue :: stockTable";
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    exception.getMessage(), exception);
        }
    }

    @GetMapping("/inventory/ledger")
    public String ledger(HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "ledger")) {
            return "redirect:/inventory";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("entries", inventoryService.ledger(clinicId(session), 100));
        return "inventory-ledger";
    }

    @GetMapping("/inventory/approvals")
    public String approvals(HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "approvals")) {
            return "redirect:/inventory";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("requests", inventoryService.pendingChangeRequests(clinicId(session)));
        return "inventory-approvals";
    }

    @PostMapping("/inventory/approvals/{id}/decide")
    public String decide(@PathVariable UUID id, @RequestParam boolean approve,
            HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "approvals")) {
            return "redirect:/inventory";
        }
        try {
            inventoryService.applyItemChange(clinicId(session), actor(session), id, approve);
            log(session, approve ? "inventory.approval.approve" : "inventory.approval.reject",
                    "inventory_change_request");
            return "redirect:/inventory/approvals";
        } catch (IllegalArgumentException exception) {
            model.addAttribute("layout", layoutModel.forRequest(session, AREA));
            model.addAttribute("requests", inventoryService.pendingChangeRequests(clinicId(session)));
            Toasts.error(model, exception.getMessage());
            return "inventory-approvals";
        }
    }

    private void renderItems(HttpSession session, Model model, boolean includeArchived) {
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("items", inventoryService.items(clinicId(session), includeArchived));
        model.addAttribute("includeArchived", includeArchived);
        model.addAttribute("form", new ItemForm());
    }

    private void renderStock(HttpSession session, Model model) {
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("stock", inventoryService.stock(clinicId(session), LocationKind.store));
    }

    private static ChangeRequestKind changeKind(String kind) {
        return switch (kind) {
            case "edit" -> ChangeRequestKind.edit;
            case "delete" -> ChangeRequestKind.delete;
            default -> throw new IllegalArgumentException("نوع الطلب غير صالح");
        };
    }

    private Actor actor(HttpSession session) {
        return new Actor(AdminAccess.membershipId(session), AdminAccess.roleCode(session));
    }

    private void log(HttpSession session, String action, String entityType) {
        activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session),
                action, entityType);
    }

    private static boolean hasSession(HttpSession session) {
        return session.getAttribute(SessionKeys.CLINIC_ID) instanceof UUID
                && session.getAttribute(SessionKeys.MEMBERSHIP_ID) instanceof UUID
                && session.getAttribute(SessionKeys.ROLE_CODE) instanceof String;
    }

    private static UUID clinicId(HttpSession session) {
        return AdminAccess.clinicId(session);
    }

    private void writeLastSectionCookie(HttpServletResponse response, String route) {
        Cookie cookie = new Cookie("lastSection", route);
        cookie.setPath("/");
        cookie.setMaxAge(30 * 24 * 60 * 60);
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    record SubArea(String route, String code, String title) {
    }

    public static class ItemForm {
        private String name;
        private String uom;
        private Integer unitsPerPack;
        private BigDecimal unitCost;
        private Integer storeAlert;
        private Integer trayAlert;

        ItemRequest toRequest() {
            return new ItemRequest(name, uom, unitsPerPack, unitCost, storeAlert, trayAlert);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUom() {
            return uom;
        }

        public void setUom(String uom) {
            this.uom = uom;
        }

        public Integer getUnitsPerPack() {
            return unitsPerPack;
        }

        public void setUnitsPerPack(Integer unitsPerPack) {
            this.unitsPerPack = unitsPerPack;
        }

        public BigDecimal getUnitCost() {
            return unitCost;
        }

        public void setUnitCost(BigDecimal unitCost) {
            this.unitCost = unitCost;
        }

        public Integer getStoreAlert() {
            return storeAlert;
        }

        public void setStoreAlert(Integer storeAlert) {
            this.storeAlert = storeAlert;
        }

        public Integer getTrayAlert() {
            return trayAlert;
        }

        public void setTrayAlert(Integer trayAlert) {
            this.trayAlert = trayAlert;
        }
    }
}