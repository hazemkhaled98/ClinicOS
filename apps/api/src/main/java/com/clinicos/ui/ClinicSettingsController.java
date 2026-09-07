package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.ClinicSettingsService.CategoryWeight;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettingsValidationException;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Tier;
import com.clinicos.identity.api.SessionKeys;

import jakarta.servlet.http.HttpSession;

/**
 * The four clinic-settings cards under the admin settings tab (screen 39 cards
 * 2-3 plus the clause-addendum duty and tier cards). Each card posts its own
 * HTMX form and re-renders only its fragment; the page GET
 * ({@link AdminController#settings}) supplies the initial {@code settings}
 * aggregate plus null error maps for every card.
 *
 * <p>Model attributes shared with {@code admin/settings} and
 * {@code admin/clinic-settings}: {@code settings}, {@code weightErrors},
 * {@code volumeErrors}, {@code dutyErrors}, {@code tierErrors}.
 */
@Controller
public class ClinicSettingsController {

    private final LayoutModel layoutModel;
    private final ClinicSettingsService clinicSettingsService;

    public ClinicSettingsController(LayoutModel layoutModel, ClinicSettingsService clinicSettingsService) {
        this.layoutModel = layoutModel;
        this.clinicSettingsService = clinicSettingsService;
    }

    @PostMapping("/admin-dashboard/settings/weights")
    public String updateWeights(@RequestParam Map<String, String> params,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        List<CategoryWeight> weights = new ArrayList<>();
        for (Category category : Category.values()) {
            String raw = params.get("weight-" + category.code());
            BigDecimal parsed = parseAmount(raw, "weights", fieldErrors, "أوزان مكونات التقييم غير صحيحة");
            weights.add(new CategoryWeight(category, parsed));
        }
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateWeights(clinicId(session), weights);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        return renderWeightsCard(model, clinicId(session), weights, fieldErrors);
    }

    @PostMapping("/admin-dashboard/settings/volume")
    public String updateVolumeTarget(@RequestParam Map<String, String> params,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        BigDecimal volumeTarget = parseAmount(params.get("volumeTarget"), "volumeTarget",
                fieldErrors, "هدف الفواتير الشهري غير صحيح");
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateVolumeTarget(clinicId(session), volumeTarget);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        return renderCard(model, session, "volumeErrors", fieldErrors, "volumeCard");
    }

    @PostMapping("/admin-dashboard/settings/duty")
    public String updateDuty(@RequestParam Map<String, String> params,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        LocalTime shiftStart = parseTime(params.get("defaultShiftStart"), "shift", fieldErrors);
        LocalTime shiftEnd = parseTime(params.get("defaultShiftEnd"), "shift", fieldErrors);
        Integer grace = parseInt(params.get("lateGraceMinutes"), "lateGraceMinutes", fieldErrors,
                "مهلة التأخير غير صحيحة");
        Integer workingDays = parseInt(params.get("workingDaysPerMonth"), "workingDaysPerMonth",
                fieldErrors, "أيام العمل الشهرية غير صحيحة");
        Integer academyScore = parseInt(params.get("academyPassScore"), "academyPassScore",
                fieldErrors, "درجة النجاح غير صحيحة");
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateDuty(clinicId(session), shiftStart, shiftEnd,
                        grace, workingDays, academyScore);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        return renderCard(model, session, "dutyErrors", fieldErrors, "dutyCard");
    }

    @PostMapping("/admin-dashboard/settings/tiers")
    public String updateTiers(@RequestParam Map<String, String> params,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        List<Tier> tiers = new ArrayList<>();
        for (int i = 0; params.containsKey("tierName" + i); i++) {
            String name = params.get("tierName" + i);
            BigDecimal minScore = parseAmount(params.get("tierMinScore" + i), "tiers", fieldErrors,
                    "قيم الشرائح غير صحيحة");
            BigDecimal pct = parseAmount(params.get("tierPct" + i), "tiers", fieldErrors,
                    "قيم الشرائح غير صحيحة");
            tiers.add(new Tier(name == null ? "" : name.trim(), minScore, pct));
        }
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateTiers(clinicId(session), tiers);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        return renderTiersCard(model, clinicId(session), tiers, fieldErrors);
    }

    private String renderCard(Model model, HttpSession session, String errorAttr,
            Map<String, String> fieldErrors, String cardFragment) {
        model.addAttribute("settings", clinicSettingsService.get(clinicId(session)));
        model.addAttribute(errorAttr, fieldErrors.isEmpty() ? null : fieldErrors);
        return "admin/clinic-settings :: " + cardFragment;
    }

    private String renderWeightsCard(Model model, UUID clinicId, List<CategoryWeight> submittedWeights,
            Map<String, String> fieldErrors) {
        var settings = clinicSettingsService.get(clinicId);
        model.addAttribute("settings", settings);
        model.addAttribute("weights", fieldErrors.isEmpty() ? settings.weights() : submittedWeights);
        model.addAttribute("weightErrors", fieldErrors.isEmpty() ? null : fieldErrors);
        return "admin/clinic-settings :: weightsCard";
    }

    private String renderTiersCard(Model model, UUID clinicId, List<Tier> submittedTiers,
            Map<String, String> fieldErrors) {
        var settings = clinicSettingsService.get(clinicId);
        model.addAttribute("settings", settings);
        model.addAttribute("tiers", fieldErrors.isEmpty() ? settings.tiers() : submittedTiers);
        model.addAttribute("tierErrors", fieldErrors.isEmpty() ? null : fieldErrors);
        return "admin/clinic-settings :: tiersCard";
    }

    private static UUID clinicId(HttpSession session) {
        return (UUID) session.getAttribute(SessionKeys.CLINIC_ID);
    }

    private static BigDecimal parseAmount(String value, String key, Map<String, String> errors, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            errors.put(key, message);
            return null;
        }
    }

    private static Integer parseInt(String value, String key, Map<String, String> errors, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            errors.put(key, message);
            return null;
        }
    }

    private static LocalTime parseTime(String value, String key, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            errors.put(key, "وقت غير صحيح");
        }
        return null;
    }
}