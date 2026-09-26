package com.clinicos.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.AutoPopulatingList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.BadgeThreshold;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
import com.clinicos.clinicconfig.api.GamificationService.WeeklyGoal;
import com.clinicos.shared.ActivityLogService;

import jakarta.servlet.http.HttpSession;

@Controller
public class GamificationController {

    private static final Logger log = LoggerFactory.getLogger(GamificationController.class);

    private final LayoutModel layoutModel;
    private final GamificationService gamificationService;
    private final ActivityLogService activityLogService;

    public GamificationController(LayoutModel layoutModel, GamificationService gamificationService,
            ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.gamificationService = gamificationService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin-dashboard/goals")
    public String goals(HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        renderPage(model, clinicId);
        return "admin/gamification-page";
    }

    @PostMapping("/admin-dashboard/goals/settings")
    public String updateSettings(
            @RequestParam(defaultValue = "false") boolean showLevelRing,
            @RequestParam(defaultValue = "false") boolean showStreaks,
            @RequestParam(defaultValue = "false") boolean showBadges,
            @RequestParam(defaultValue = "false") boolean showWeeklyGoals,
            @RequestParam(defaultValue = "false") boolean showLeaderboard,
            @RequestParam(defaultValue = "false") boolean showReward,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        gamificationService.updateSettings(clinicId, new GamificationSettings(
                showLevelRing, showStreaks, showBadges, showWeeklyGoals, showLeaderboard, showReward),
                AdminAccess.membershipId(session));
        activityLogService.log(clinicId, AdminAccess.membershipId(session), "gamification.settings", "gamification_settings");
        Toasts.success(model, "تم حفظ إعدادات التحفيز");
        renderPage(model, clinicId);
        return "admin/gamification :: settingsCard";
    }

    @PostMapping("/admin-dashboard/goals/goals")
    public String updateGoals(GoalsForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> errors = new HashMap<>();
        List<GoalsForm.GoalRow> rows = form.goals;
        if (rows.size() > 3) {
            errors.put("goals", "لا يمكن حفظ أكثر من 3 أهداف");
        }
        int[] parsedTargets = parseIntsOrZero(rows.stream().map(r -> r.target).toList(),
                "targets", "قيمة الهدف يجب أن تكون رقماً", errors);
        if (errors.isEmpty()) {
            try {
                for (int i = 0; i < rows.size(); i++) {
                    GoalsForm.GoalRow row = rows.get(i);
                    if (row.title == null || row.title.isBlank()) {
                        continue;
                    }
                    gamificationService.updateGoal(clinicId, i + 1, row.title.trim(), parsedTargets[i],
                            AdminAccess.membershipId(session));
                }
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "gamification.goals", "weekly_goal");
            } catch (IllegalArgumentException e) {
                errors.put("goals", e.getMessage());
            }
        }
        Toasts.fromErrors(model, errors, "تم حفظ الأهداف الأسبوعية");
        renderPage(model, clinicId);
        return "admin/gamification :: goalsCard";
    }

    @PostMapping("/admin-dashboard/goals/thresholds")
    public String updateThresholds(ThresholdsForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> errors = new HashMap<>();
        List<ThresholdsForm.ThresholdRow> rows = form.thresholds;
        int[] parsedThresholds = parseIntsOrZero(rows.stream().map(r -> r.threshold).toList(),
                "thresholds", "عدد المهام يجب أن يكون رقماً", errors);
        if (errors.isEmpty()) {
            try {
                for (int i = 0; i < rows.size(); i++) {
                    gamificationService.updateThreshold(clinicId, rows.get(i).name, parsedThresholds[i],
                            AdminAccess.membershipId(session));
                }
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "gamification.thresholds", "badge_threshold");
            } catch (IllegalArgumentException e) {
                errors.put("thresholds", e.getMessage());
            }
        }
        Toasts.fromErrors(model, errors, "تم حفظ شروط الشارات");
        renderPage(model, clinicId);
        return "admin/gamification :: thresholdsCard";
    }

    private static int[] parseIntsOrZero(List<String> rawValues, String field, String message,
            Map<String, String> errors) {
        int[] parsed = new int[rawValues.size()];
        for (int i = 0; i < rawValues.size() && errors.isEmpty(); i++) {
            String raw = rawValues.get(i);
            Integer value = FormParsing.parseInt(raw, field, errors, message);
            parsed[i] = value == null ? 0 : value;
        }
        return parsed;
    }

    private void renderPage(Model model, UUID clinicId) {
        GamificationSettings settings = gamificationService.get(clinicId);
        if (settings == null) {
            log.warn("gamification settings missing for clinic {}", clinicId);
            settings = new GamificationSettings(false, false, false, false, false, false);
        }
        model.addAttribute("settings", settings);
        model.addAttribute("goalForm", GoalsForm.from(gamificationService.getGoals(clinicId)));
        model.addAttribute("thresholdForm", ThresholdsForm.from(gamificationService.getThresholds(clinicId)));
    }

    public static class GoalsForm {
        private AutoPopulatingList<GoalRow> goals = new AutoPopulatingList<>(GoalRow.class);

        public List<GoalRow> getGoals() {
            return goals;
        }

        public void setGoals(List<GoalRow> goals) {
            this.goals = new AutoPopulatingList<>(goals, GoalRow.class);
        }

        static GoalsForm from(List<WeeklyGoal> saved) {
            GoalsForm form = new GoalsForm();
            for (int slot = 1; slot <= 3; slot++) {
                GoalRow row = new GoalRow();
                final int s = slot;
                saved.stream().filter(g -> g.slot() == s).findFirst().ifPresent(g -> {
                    row.title = g.title();
                    row.target = g.target() == 0 ? "" : Integer.toString(g.target());
                });
                form.goals.add(row);
            }
            return form;
        }

        public static class GoalRow {
            private String title;
            private String target;

            public String getTitle() {
                return title;
            }

            public void setTitle(String title) {
                this.title = title;
            }

            public String getTarget() {
                return target;
            }

            public void setTarget(String target) {
                this.target = target;
            }
        }
    }

    public static class ThresholdsForm {
        private AutoPopulatingList<ThresholdRow> thresholds = new AutoPopulatingList<>(ThresholdRow.class);

        public List<ThresholdRow> getThresholds() {
            return thresholds;
        }

        public void setThresholds(List<ThresholdRow> thresholds) {
            this.thresholds = new AutoPopulatingList<>(thresholds, ThresholdRow.class);
        }

        static ThresholdsForm from(List<BadgeThreshold> saved) {
            ThresholdsForm form = new ThresholdsForm();
            for (String name : List.of("نجم الأسبوع", "الأكثر إنجازاً", "مبدع", "ملتزم", "متميز")) {
                ThresholdRow row = new ThresholdRow();
                row.name = name;
                saved.stream().filter(t -> t.name().equals(name)).findFirst().ifPresent(t ->
                        row.threshold = t.threshold() == 0 ? "" : Integer.toString(t.threshold()));
                form.thresholds.add(row);
            }
            return form;
        }

        public static class ThresholdRow {
            private String name;
            private String threshold;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getThreshold() {
                return threshold;
            }

            public void setThreshold(String threshold) {
                this.threshold = threshold;
            }
        }
    }
}