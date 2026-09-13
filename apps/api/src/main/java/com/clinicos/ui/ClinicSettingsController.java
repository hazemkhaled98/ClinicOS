package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.AutoPopulatingList;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.ClinicSettingsService.CategoryWeight;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettingsValidationException;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Tier;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.clinicconfig.api.WorkCalendarService.HolidayRequest;
import com.clinicos.staff.api.EmployeeService;

import jakarta.servlet.http.HttpSession;

/**
 * The four clinic-settings cards under the admin settings tab (screen 39 cards
 * 2-3 plus the clause-addendum duty and tier cards). Each card posts its own
 * HTMX form and re-renders only its fragment. Errors surface as toasts; the
 * POST handlers re-render the card preserving submitted values.
 */
@Controller
public class ClinicSettingsController {

    private final LayoutModel layoutModel;
    private final ClinicSettingsService clinicSettingsService;
    private final WorkCalendarService workCalendarService;
    private final EmployeeService employeeService;

    public ClinicSettingsController(LayoutModel layoutModel, ClinicSettingsService clinicSettingsService,
            WorkCalendarService workCalendarService, EmployeeService employeeService) {
        this.layoutModel = layoutModel;
        this.clinicSettingsService = clinicSettingsService;
        this.workCalendarService = workCalendarService;
        this.employeeService = employeeService;
    }

    @PostMapping("/admin-dashboard/settings/weights")
    public String updateWeights(WeightsForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        List<CategoryWeight> weights = toWeights(form, fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateWeights(AdminAccess.clinicId(session), weights);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        Toasts.fromErrors(model, fieldErrors, "تم حفظ الأوزان");
        List<CategoryWeight> summedWeights = fieldErrors.isEmpty()
                ? clinicSettingsService.get(AdminAccess.clinicId(session)).weights()
                : weights;
        model.addAttribute("weightsForm", fieldErrors.isEmpty() ? WeightsForm.from(summedWeights) : form);
        model.addAttribute("weightsSum", sumWeights(summedWeights));
        return "admin/clinic-settings :: weightsCard";
    }

    @PostMapping("/admin-dashboard/settings/volume")
    public String updateVolumeTarget(VolumeForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        BigDecimal volumeTarget = FormParsing.parseAmount(form.getVolumeTarget(), "volumeTarget",
                fieldErrors, "هدف الفواتير الشهري غير صحيح");
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateVolumeTarget(AdminAccess.clinicId(session), volumeTarget);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        Toasts.fromErrors(model, fieldErrors, "تم حفظ الهدف الشهري");
        model.addAttribute("volumeForm", form);
        return "admin/clinic-settings :: volumeCard";
    }

    @PostMapping("/admin-dashboard/settings/duty")
    public String updateDuty(DutyForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        LocalTime shiftStart = FormParsing.parseTime(form.getDefaultShiftStart(), "shift", fieldErrors);
        LocalTime shiftEnd = FormParsing.parseTime(form.getDefaultShiftEnd(), "shift", fieldErrors);
        Integer grace = requiredInt(form.getLateGraceMinutes(), "lateGraceMinutes", "مهلة التأخير غير صحيحة", fieldErrors);
        Integer workingDays = requiredInt(form.getWorkingDaysPerMonth(), "workingDaysPerMonth",
                "أيام العمل الشهرية غير صحيحة", fieldErrors);
        Integer academyScore = requiredInt(form.getAcademyPassScore(), "academyPassScore",
                "درجة النجاح غير صحيحة", fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateDuty(AdminAccess.clinicId(session), shiftStart, shiftEnd,
                        grace, workingDays, academyScore);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        Toasts.fromErrors(model, fieldErrors, "تم حفظ الدوام");
        model.addAttribute("dutyForm", form);
        return "admin/clinic-settings :: dutyCard";
    }

    @PostMapping("/admin-dashboard/settings/tiers")
    public String updateTiers(TiersForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        List<Tier> tiers = toTiers(form, fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                clinicSettingsService.updateTiers(AdminAccess.clinicId(session), tiers);
            } catch (ClinicSettingsValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        Toasts.fromErrors(model, fieldErrors, "تم حفظ الشرائح");
        TiersForm rendered = fieldErrors.isEmpty()
                ? TiersForm.from(clinicSettingsService.get(AdminAccess.clinicId(session)).tiers())
                : form;
        model.addAttribute("tiersForm", rendered);
        return "admin/clinic-settings :: tiersCard";
    }

    @PostMapping("/admin-dashboard/settings/calendar-weekdays")
    public String updateWeekdays(WeekdaysForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        List<Integer> weekdays = form.getWeekdays() == null ? List.of() : form.getWeekdays();
        try {
            workCalendarService.setWorkingWeekdays(AdminAccess.clinicId(session), weekdays);
        } catch (IllegalArgumentException e) {
            fieldErrors.put("weekdays", e.getMessage());
        }
        Toasts.fromErrors(model, fieldErrors, "تم حفظ أيام العمل");
        WeekdaysForm rendered = fieldErrors.isEmpty()
                ? WeekdaysForm.from(workCalendarService.workingWeekdays(AdminAccess.clinicId(session)))
                : form;
        renderCalendarCard(model, session, rendered, new HolidayForm());
        return "admin/clinic-settings :: calendarCard";
    }

    @PostMapping("/admin-dashboard/settings/holidays")
    public String addHoliday(HolidayForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        LocalDate date = parseDate(form.getDate(), "date", "تاريخ الإجازة غير صحيح", fieldErrors);
        if (form.getName() == null || form.getName().isBlank()) {
            fieldErrors.put("name", "اسم الإجازة مطلوب");
        }
        UUID employeeId = parseUuid(form.getEmployeeId(), fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                workCalendarService.addHoliday(AdminAccess.clinicId(session),
                        new HolidayRequest(date, form.getName().trim(), employeeId));
            } catch (IllegalArgumentException e) {
                fieldErrors.put("holiday", e.getMessage());
            }
        }
        Toasts.fromErrors(model, fieldErrors, "تمت إضافة الإجازة");
        renderCalendarCard(model, session,
                WeekdaysForm.from(workCalendarService.workingWeekdays(AdminAccess.clinicId(session))),
                fieldErrors.isEmpty() ? new HolidayForm() : form);
        return "admin/clinic-settings :: calendarCard";
    }

    @DeleteMapping("/admin-dashboard/settings/holidays/{holidayId}")
    public String deleteHoliday(@PathVariable UUID holidayId, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            workCalendarService.removeHoliday(AdminAccess.clinicId(session), holidayId);
        } catch (IllegalArgumentException e) {
            fieldErrors.put("holiday", e.getMessage());
        }
        Toasts.fromErrors(model, fieldErrors, "تم حذف الإجازة");
        renderCalendarCard(model, session,
                WeekdaysForm.from(workCalendarService.workingWeekdays(AdminAccess.clinicId(session))),
                new HolidayForm());
        return "admin/clinic-settings :: calendarCard";
    }

    private void renderCalendarCard(Model model, HttpSession session,
            WeekdaysForm weekdaysForm, HolidayForm holidaysForm) {
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("weekdaysForm", weekdaysForm);
        model.addAttribute("holidaysForm", holidaysForm);
        model.addAttribute("holidays", workCalendarService.listHolidays(clinicId));
        model.addAttribute("employees", employeeService.list(clinicId));
    }

    private static LocalDate parseDate(String value, String key, String message, Map<String, String> fieldErrors) {
        if (value == null || value.isBlank()) {
            fieldErrors.put(key, message);
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            fieldErrors.put(key, message);
            return null;
        }
    }

    private static UUID parseUuid(String value, Map<String, String> fieldErrors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            fieldErrors.put("employeeId", "الموظف المحدد غير صحيح");
            return null;
        }
    }

    private static Integer requiredInt(String raw, String field, String message, Map<String, String> fieldErrors) {
        Integer parsed = FormParsing.parseInt(raw, field, fieldErrors, message);
        if (parsed == null && !fieldErrors.containsKey(field)) {
            fieldErrors.put(field, message);
        }
        return parsed == null ? 0 : parsed;
    }

    static List<CategoryWeight> toWeights(WeightsForm form, Map<String, String> fieldErrors) {
        List<CategoryWeight> weights = new ArrayList<>();
        for (WeightsForm.WeightRow row : form.weights) {
            try {
                weights.add(new CategoryWeight(Category.fromCode(row.category),
                        FormParsing.parseAmount(row.weight, "weights", fieldErrors, "أوزان مكونات التقييم غير صحيحة")));
            } catch (IllegalArgumentException e) {
                fieldErrors.put("weights", e.getMessage());
            }
        }
        return weights;
    }

    private static List<Tier> toTiers(TiersForm form, Map<String, String> fieldErrors) {
        List<Tier> tiers = new ArrayList<>();
        for (TiersForm.TierRow row : form.tiers) {
            BigDecimal minScore = FormParsing.parseAmount(row.minScore, "tiers", fieldErrors, "قيم الشرائح غير صحيحة");
            BigDecimal pct = FormParsing.parseAmount(row.incentivePct, "tiers", fieldErrors, "قيم الشرائح غير صحيحة");
            tiers.add(new Tier(row.name == null ? "" : row.name.trim(), minScore, pct));
        }
        return tiers;
    }

    static String sumWeights(List<ClinicSettingsService.CategoryWeight> weights) {
        return weights == null ? "0" : weights.stream()
                .map(ClinicSettingsService.CategoryWeight::weight)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .stripTrailingZeros().toPlainString();
    }

    public static class WeightsForm {
        private AutoPopulatingList<WeightRow> weights = new AutoPopulatingList<>(WeightRow.class);

        public List<WeightRow> getWeights() {
            return weights;
        }

        public void setWeights(List<WeightRow> weights) {
            this.weights = new AutoPopulatingList<>(weights, WeightRow.class);
        }

        static WeightsForm from(List<CategoryWeight> saved) {
            WeightsForm form = new WeightsForm();
            for (CategoryWeight categoryWeight : saved) {
                WeightRow row = new WeightRow();
                row.category = categoryWeight.category().code();
                row.weight = categoryWeight.weight() == null ? "" : categoryWeight.weight().stripTrailingZeros().toPlainString();
                form.weights.add(row);
            }
            return form;
        }

        public static class WeightRow {
            private String category;
            private String weight;

            public String getCategory() {
                return category;
            }

            public void setCategory(String category) {
                this.category = category;
            }

            public String getWeight() {
                return weight;
            }

            public void setWeight(String weight) {
                this.weight = weight;
            }
        }
    }

    public static class VolumeForm {
        private String volumeTarget;

        public String getVolumeTarget() {
            return volumeTarget;
        }

        public void setVolumeTarget(String volumeTarget) {
            this.volumeTarget = volumeTarget;
        }

        static VolumeForm from(BigDecimal saved) {
            VolumeForm form = new VolumeForm();
            form.volumeTarget = saved == null ? "" : saved.stripTrailingZeros().toPlainString();
            return form;
        }
    }

    public static class DutyForm {
        private String defaultShiftStart;
        private String defaultShiftEnd;
        private String lateGraceMinutes;
        private String workingDaysPerMonth;
        private String academyPassScore;

        public String getDefaultShiftStart() {
            return defaultShiftStart;
        }

        public void setDefaultShiftStart(String defaultShiftStart) {
            this.defaultShiftStart = defaultShiftStart;
        }

        public String getDefaultShiftEnd() {
            return defaultShiftEnd;
        }

        public void setDefaultShiftEnd(String defaultShiftEnd) {
            this.defaultShiftEnd = defaultShiftEnd;
        }

        public String getLateGraceMinutes() {
            return lateGraceMinutes;
        }

        public void setLateGraceMinutes(String lateGraceMinutes) {
            this.lateGraceMinutes = lateGraceMinutes;
        }

        public String getWorkingDaysPerMonth() {
            return workingDaysPerMonth;
        }

        public void setWorkingDaysPerMonth(String workingDaysPerMonth) {
            this.workingDaysPerMonth = workingDaysPerMonth;
        }

        public String getAcademyPassScore() {
            return academyPassScore;
        }

        public void setAcademyPassScore(String academyPassScore) {
            this.academyPassScore = academyPassScore;
        }

        static DutyForm from(ClinicSettingsService.ClinicSettings settings) {
            DutyForm form = new DutyForm();
            form.defaultShiftStart = settings.defaultShiftStart() == null ? "" : settings.defaultShiftStart().toString();
            form.defaultShiftEnd = settings.defaultShiftEnd() == null ? "" : settings.defaultShiftEnd().toString();
            form.lateGraceMinutes = Integer.toString(settings.lateGraceMinutes());
            form.workingDaysPerMonth = Integer.toString(settings.workingDaysPerMonth());
            form.academyPassScore = Integer.toString(settings.academyPassScore());
            return form;
        }
    }

    public static class TiersForm {
        private AutoPopulatingList<TierRow> tiers = new AutoPopulatingList<>(TierRow.class);

        public List<TierRow> getTiers() {
            return tiers;
        }

        public void setTiers(List<TierRow> tiers) {
            this.tiers = new AutoPopulatingList<>(tiers, TierRow.class);
        }

        static TiersForm from(List<Tier> saved) {
            TiersForm form = new TiersForm();
            for (Tier tier : saved) {
                TierRow row = new TierRow();
                row.name = tier.name();
                row.minScore = tier.minScore() == null ? "" : tier.minScore().stripTrailingZeros().toPlainString();
                row.incentivePct = tier.incentivePct() == null ? "" : tier.incentivePct().stripTrailingZeros().toPlainString();
                form.tiers.add(row);
            }
            return form;
        }

        public static class TierRow {
            private String name;
            private String minScore;
            private String incentivePct;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getMinScore() {
                return minScore;
            }

            public void setMinScore(String minScore) {
                this.minScore = minScore;
            }

            public String getIncentivePct() {
                return incentivePct;
            }

            public void setIncentivePct(String incentivePct) {
                this.incentivePct = incentivePct;
            }
        }
    }

    public static class WeekdaysForm {
        private List<Integer> weekdays = new ArrayList<>();

        public List<Integer> getWeekdays() {
            return weekdays;
        }

        public void setWeekdays(List<Integer> weekdays) {
            this.weekdays = weekdays == null ? new ArrayList<>() : weekdays;
        }

        public boolean isChecked(int isoDay) {
            return weekdays.contains(isoDay);
        }

        static WeekdaysForm from(List<Integer> saved) {
            WeekdaysForm form = new WeekdaysForm();
            if (saved != null) {
                form.weekdays = new ArrayList<>(saved);
            }
            return form;
        }
    }

    public static class HolidayForm {
        private String date;
        private String name;
        private String employeeId;

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmployeeId() {
            return employeeId;
        }

        public void setEmployeeId(String employeeId) {
            this.employeeId = employeeId;
        }
    }
}