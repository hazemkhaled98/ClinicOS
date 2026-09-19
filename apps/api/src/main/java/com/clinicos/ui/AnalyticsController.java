package com.clinicos.ui;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.inventory.InventoryAnalyticsService;
import com.clinicos.shared.ActivityLogService;

import jakarta.servlet.http.HttpSession;

@Controller
public class AnalyticsController {

    private static final String AREA = "inventory";
    private final LayoutModel layoutModel;
    private final InventoryAnalyticsService analyticsService;

    public AnalyticsController(LayoutModel layoutModel, InventoryAnalyticsService analyticsService,
            ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/inventory/dash")
    public String dashboard(HttpSession session, Model model) {
        if (!allowed(session, "dash")) return "redirect:/inventory";
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("dashboard", analyticsService.dashboard(clinicId(session)));
        return "inventory-dash";
    }

    @GetMapping("/inventory/profit")
    public String profit(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpSession session, Model model) {
        if (!allowed(session, "profit")) return "redirect:/inventory";
        render(model, session, "الربحية");
        model.addAttribute("rows", analyticsService.profit(clinicId(session), from, to));
        return "inventory-profit";
    }

    @GetMapping("/inventory/analytics")
    public String analytics(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpSession session, Model model) {
        if (!allowed(session, "analytics")) return "redirect:/inventory";
        render(model, session, "تحليل الاستهلاك");
        model.addAttribute("rows", analyticsService.consumption(clinicId(session), from, to));
        return "inventory-analytics";
    }

    @GetMapping("/inventory/waste")
    public String waste(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpSession session, Model model) {
        if (!allowed(session, "waste")) return "redirect:/inventory";
        render(model, session, "الهدر");
        model.addAttribute("rows", analyticsService.waste(clinicId(session), from, to));
        return "inventory-waste";
    }

    @GetMapping("/inventory/doctors")
    public String doctors(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpSession session, Model model) {
        if (!allowed(session, "doctors")) return "redirect:/inventory";
        render(model, session, "تحليل الأطباء");
        model.addAttribute("rows", analyticsService.doctors(clinicId(session), from, to));
        return "inventory-doctors";
    }

    @GetMapping("/inventory/supAnalysis")
    public String suppliers(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpSession session, Model model) {
        if (!allowed(session, "supAnalysis")) return "redirect:/inventory";
        render(model, session, "تحليل الموردين");
        model.addAttribute("rows", analyticsService.suppliers(clinicId(session), from, to));
        return "inventory-supplier-analytics";
    }

    @GetMapping("/inventory/itemAnalysis")
    public String items(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            HttpSession session, Model model) {
        if (!allowed(session, "itemAnalysis")) return "redirect:/inventory";
        render(model, session, "تحليل الأصناف");
        model.addAttribute("rows", analyticsService.itemPrices(clinicId(session), from, to));
        return "inventory-item-analysis";
    }

    private void render(Model model, HttpSession session, String title) {
        model.addAttribute("layout", layoutModel.forRequest(session, AREA));
        model.addAttribute("title", title);
    }

    private boolean allowed(HttpSession session, String code) {
        return session.getAttribute(SessionKeys.CLINIC_ID) instanceof UUID
                && session.getAttribute(SessionKeys.MEMBERSHIP_ID) instanceof UUID
                && session.getAttribute(SessionKeys.ROLE_CODE) instanceof String
                && AdminAccess.hasCode(session, code);
    }

    private UUID clinicId(HttpSession session) { return AdminAccess.clinicId(session); }
}
