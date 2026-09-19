package com.clinicos.ui;

import static com.clinicos.shared.jooq.enums.PoStatus.placed;
import static com.clinicos.shared.jooq.enums.PoStatus.received;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.PurchasingService;
import com.clinicos.inventory.PurchasingService.OrderLineRequest;
import com.clinicos.inventory.PurchasingService.ReceiptLine;
import com.clinicos.inventory.PurchasingService.ReturnLineRequest;
import com.clinicos.inventory.PurchasingService.SupplierRequest;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.AttachmentService;

import jakarta.servlet.http.HttpSession;

@Controller
public class PurchasingController {

    private static final String AREA = "inventory";
    private static final Logger log = LoggerFactory.getLogger(PurchasingController.class);

    private final LayoutModel layoutModel;
    private final PurchasingService purchasingService;
    private final ActivityLogService activityLogService;
    private final AttachmentService attachmentService;

    public PurchasingController(LayoutModel layoutModel, PurchasingService purchasingService,
            ActivityLogService activityLogService, AttachmentService attachmentService) {
        this.layoutModel = layoutModel;
        this.purchasingService = purchasingService;
        this.activityLogService = activityLogService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/inventory/orders")
    public String orders(@RequestParam(required = false) UUID wa, HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "orders")) {
            return "redirect:/inventory";
        }
        renderOrders(session, model);
        if (wa != null) {
            model.addAttribute("waLink", whatsappLink(clinicId(session), wa));
        }
        return "inventory-orders";
    }

    @PostMapping("/inventory/orders")
    public String placeOrder(@RequestParam UUID supplierId,
            @RequestParam List<UUID> itemId,
            @RequestParam List<BigDecimal> qty,
            @RequestParam(required = false) List<BigDecimal> unitCost,
            HttpSession session, RedirectAttributes redirect) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "orders")) {
            return "redirect:/inventory";
        }
        List<OrderLineRequest> lines = new ArrayList<>();
        for (int i = 0; i < itemId.size(); i++) {
            BigDecimal requested = qty.get(i);
            if (requested == null || requested.signum() <= 0) {
                continue;
            }
            BigDecimal belt = unitCost != null && i < unitCost.size() ? unitCost.get(i) : null;
            lines.add(new OrderLineRequest(itemId.get(i), requested, belt));
        }
        try {
            var order = purchasingService.placeOrder(clinicId(session), actor(session), supplierId, lines);
            log(session, "inventory.order.place", "purchase_order");
            return "redirect:/inventory/orders?wa=" + order.id();
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("toastMessage", exception.getMessage());
            redirect.addFlashAttribute("toastType", "error");
            return "redirect:/inventory/orders";
        }
    }

    @GetMapping("/inventory/receive")
    public String receive(HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "receive")) {
            return "redirect:/inventory";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("orders", purchasingService.orders(clinicId(session), java.util.Set.of(placed)));
        return "inventory-receive";
    }

    @PostMapping("/inventory/receive")
    public String receiveExec(@RequestParam UUID orderId,
            @RequestParam(name = "photo", required = false) MultipartFile photo,
            @RequestParam List<UUID> lineId,
            @RequestParam List<BigDecimal> qtyReceived,
            @RequestParam(required = false) List<String> lotNumber,
            @RequestParam(required = false) List<BigDecimal> deliveryCost,
            HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "receive")) {
            return "redirect:/inventory";
        }
        UUID clinicId = clinicId(session);
        UUID photoId = null;
        try {
            photoId = photo == null || photo.isEmpty() ? null
                    : attachmentService.upload(clinicId, AdminAccess.membershipId(session), photo).id();
            List<ReceiptLine> lines = new ArrayList<>();
            for (int i = 0; i < lineId.size(); i++) {
                BigDecimal cost = deliveryCost != null && i < deliveryCost.size() ? deliveryCost.get(i) : null;
                String lot = lotNumber != null && i < lotNumber.size() ? blank(lotNumber.get(i)) : null;
                lines.add(new ReceiptLine(lineId.get(i), qtyReceived.get(i), lot, cost));
            }
            purchasingService.receive(clinicId, actor(session), orderId, lines, photoId);
            log(session, "inventory.receive", "purchase_order");
            return "redirect:/inventory/received";
        } catch (IllegalArgumentException exception) {
            if (photoId != null) {
                try {
                    attachmentService.delete(clinicId, photoId);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("failed to clean up orphaned invoice photo {} after rejected receipt: clinicId={}", photoId, clinicId, cleanupFailure);
                }
            }
            model.addAttribute("layout", layoutModel.forRequest(session, AREA));
            model.addAttribute("orders", purchasingService.orders(clinicId, java.util.Set.of(placed)));
            Toasts.error(model, exception.getMessage());
            return "inventory-receive";
        }
    }

    @GetMapping("/inventory/received")
    public String received(HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "received")) {
            return "redirect:/inventory";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("orders", purchasingService.orders(clinicId(session), java.util.Set.of(received)));
        return "inventory-received";
    }

    @GetMapping("/inventory/returns")
    public String returns(HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "returns")) {
            return "redirect:/inventory";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("orders", purchasingService.orders(clinicId(session), java.util.Set.of(received)));
        model.addAttribute("returns", purchasingService.pendingReturns(clinicId(session)));
        return "inventory-returns";
    }

    @PostMapping("/inventory/returns")
    public String requestReturn(@RequestParam UUID orderId,
            @RequestParam List<UUID> orderLineId,
            @RequestParam List<BigDecimal> qty,
            HttpSession session, RedirectAttributes redirect) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "returns")) {
            return "redirect:/inventory";
        }
        List<ReturnLineRequest> lines = new ArrayList<>();
        for (int i = 0; i < orderLineId.size(); i++) {
            BigDecimal value = qty.get(i);
            if (value != null && value.signum() > 0) {
                lines.add(new ReturnLineRequest(orderLineId.get(i), value));
            }
        }
        try {
            purchasingService.requestReturn(clinicId(session), actor(session), orderId, lines);
            log(session, "inventory.return.request", "supplier_return");
            redirect.addFlashAttribute("toastMessage", "بانتظار موافقة المدير");
            redirect.addFlashAttribute("toastType", "success");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("toastMessage", exception.getMessage());
            redirect.addFlashAttribute("toastType", "error");
        }
        return "redirect:/inventory/returns";
    }

    @GetMapping("/inventory/suppliers")
    public String suppliers(@RequestParam(defaultValue = "false") boolean includeArchived,
            HttpSession session, Model model) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "suppliers")) {
            return "redirect:/inventory";
        }
        renderSuppliers(session, model, includeArchived);
        return "inventory-suppliers";
    }

    @PostMapping("/inventory/suppliers")
    public String saveSupplier(@ModelAttribute("form") SupplierForm form, HttpSession session, RedirectAttributes redirect) {
        if (!hasSession(session) || !AdminAccess.hasCode(session, "suppliers")) {
            return "redirect:/inventory";
        }
        try {
            purchasingService.saveSupplier(clinicId(session), actor(session), form.getId(),
                    new SupplierRequest(form.getName(), form.getContact(), form.getWhatsapp(), form.getLeadDays(),
                            form.getRating(), form.isArchived()));
            log(session, "inventory.supplier.save", "supplier");
            redirect.addFlashAttribute("toastMessage", "تم حفظ المورد ✔");
            redirect.addFlashAttribute("toastType", "success");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("toastMessage", exception.getMessage());
            redirect.addFlashAttribute("toastType", "error");
        }
        return "redirect:/inventory/suppliers";
    }

    private void renderOrders(HttpSession session, Model model) {
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("shortages", purchasingService.shortages(clinicId(session)));
        model.addAttribute("suppliers", purchasingService.suppliers(clinicId(session), false));
        model.addAttribute("orders", purchasingService.orders(clinicId(session), java.util.Set.of(placed)));
    }

    private void renderSuppliers(HttpSession session, Model model, boolean includeArchived) {
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("suppliers", purchasingService.suppliers(clinicId(session), includeArchived));
        model.addAttribute("includeArchived", includeArchived);
        model.addAttribute("form", new SupplierForm());
    }

    private String whatsappLink(UUID clinicId, UUID orderId) {
        String text = purchasingService.whatsappMessage(clinicId, orderId);
        var order = purchasingService.orders(clinicId, java.util.Set.of(placed)).stream()
                .filter(o -> o.id().equals(orderId)).findFirst().orElse(null);
        if (order == null) {
            return null;
        }
        String number = purchasingService.suppliers(clinicId, true).stream()
                .filter(s -> s.id().equals(order.supplierId())).findFirst()
                .map(s -> s.whatsapp()).orElse(null);
        if (number == null || number.isBlank()) {
            return null;
        }
        return "https://wa.me/" + number.replaceAll("\\D", "")
                + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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

    public static class SupplierForm {
        private UUID id;
        private String name;
        private String contact;
        private String whatsapp;
        private Integer leadDays;
        private BigDecimal rating;
        private boolean archived;

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getContact() {
            return contact;
        }

        public void setContact(String contact) {
            this.contact = contact;
        }

        public String getWhatsapp() {
            return whatsapp;
        }

        public void setWhatsapp(String whatsapp) {
            this.whatsapp = whatsapp;
        }

        public Integer getLeadDays() {
            return leadDays;
        }

        public void setLeadDays(Integer leadDays) {
            this.leadDays = leadDays;
        }

        public BigDecimal getRating() {
            return rating;
        }

        public void setRating(BigDecimal rating) {
            this.rating = rating;
        }

        public boolean isArchived() {
            return archived;
        }

        public void setArchived(boolean archived) {
            this.archived = archived;
        }
    }
}