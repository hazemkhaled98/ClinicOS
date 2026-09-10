package com.clinicos.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
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
        model.addAttribute("gamificationSettings", gamificationService.get(clinicId));
        model.addAttribute("weeklyGoals", gamificationService.getGoals(clinicId));
        model.addAttribute("badgeThresholds", gamificationService.getThresholds(clinicId));
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
                showLevelRing, showStreaks, showBadges, showWeeklyGoals, showLeaderboard, showReward));
        activityLogService.log(clinicId, AdminAccess.membershipId(session), "gamification.settings", "gamification_settings");
        renderPage(model, clinicId);
        return "admin/gamification :: settingsCard";
    }

    @PostMapping("/admin-dashboard/goals/goals")
    public String updateGoals(
            @RequestParam String[] titles,
            @RequestParam String[] targets,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> errors = new HashMap<>();
        if (titles.length != targets.length) {
            errors.put("targets", "عدد الأهداف والأسماء غير متطابق");
        }
        int[] parsedTargets = new int[titles.length];
        for (int i = 0; i < titles.length && i < 3; i++) {
            try {
                parsedTargets[i] = (i < targets.length) ? Integer.parseInt(targets[i]) : 0;
            } catch (NumberFormatException e) {
                errors.put("targets", "قيمة الهدف يجب أن تكون رقماً");
                break;
            }
        }
        if (errors.isEmpty()) {
            try {
                for (int i = 0; i < titles.length && i < 3; i++) {
                    gamificationService.updateGoal(clinicId, i + 1, titles[i], parsedTargets[i]);
                }
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "gamification.goals", "weekly_goal");
            } catch (IllegalArgumentException e) {
                errors.put("goals", e.getMessage());
            }
        }
        model.addAttribute("goalErrors", errors);
        renderPage(model, clinicId);
        return "admin/gamification :: goalsCard";
    }

    @PostMapping("/admin-dashboard/goals/thresholds")
    public String updateThresholds(
            @RequestParam String[] names,
            @RequestParam String[] thresholds,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> errors = new HashMap<>();
        int[] parsedThresholds = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            try {
                parsedThresholds[i] = (i < thresholds.length) ? Integer.parseInt(thresholds[i]) : 0;
            } catch (NumberFormatException e) {
                errors.put("thresholds", "عدد المهام يجب أن يكون رقماً");
                break;
            }
        }
        if (errors.isEmpty()) {
            try {
                for (int i = 0; i < names.length; i++) {
                    gamificationService.updateThreshold(clinicId, names[i], parsedThresholds[i]);
                }
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "gamification.thresholds", "badge_threshold");
            } catch (IllegalArgumentException e) {
                errors.put("thresholds", e.getMessage());
            }
        }
        model.addAttribute("thresholdErrors", errors);
        renderPage(model, clinicId);
        return "admin/gamification :: thresholdsCard";
    }

    private void renderPage(Model model, UUID clinicId) {
        model.addAttribute("gamificationSettings", gamificationService.get(clinicId));
        model.addAttribute("weeklyGoals", gamificationService.getGoals(clinicId));
        model.addAttribute("badgeThresholds", gamificationService.getThresholds(clinicId));
    }
}
