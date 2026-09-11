package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.BadgeThreshold;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
import com.clinicos.clinicconfig.api.GamificationService.WeeklyGoal;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class GamificationControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private LayoutModel layoutModel;
    private GamificationService gamificationService;
    private ActivityLogService activityLogService;
    private GamificationController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        gamificationService = mock(GamificationService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new GamificationController(layoutModel, gamificationService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void goalsRendersPageForAdmin() {
        HttpSession session = session();
        allowDashboard();
        GamificationSettings settings = new GamificationSettings(true, true, true, true, false, true);
        when(gamificationService.get(CLINIC)).thenReturn(settings);
        when(gamificationService.getGoals(CLINIC)).thenReturn(List.of());
        when(gamificationService.getThresholds(CLINIC)).thenReturn(List.of());

        String view = controller.goals(session, model);

        assertThat(view).isEqualTo("admin/gamification-page");
        assertThat(model.getAttribute("settings")).isEqualTo(settings);
        assertThat(model.getAttribute("goals")).isEqualTo(List.of());
        assertThat(model.getAttribute("thresholds")).isEqualTo(List.of());
    }

    @Test
    void goalsRedirectsHomeWithoutPermission() {
        HttpSession session = session();
        denyDashboard();

        String view = controller.goals(session, model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void updateSettingsLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        GamificationSettings settings = new GamificationSettings(true, false, true, false, false, true);
        when(gamificationService.get(CLINIC)).thenReturn(settings);
        when(gamificationService.getGoals(CLINIC)).thenReturn(List.of());
        when(gamificationService.getThresholds(CLINIC)).thenReturn(List.of());

        controller.updateSettings(true, false, true, false, false, true, session, model);

        verify(gamificationService).updateSettings(eq(CLINIC), any(GamificationSettings.class));
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "gamification.settings", "gamification_settings");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ إعدادات التحفيز");
    }

    @Test
    void updateGoalsLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        when(gamificationService.get(CLINIC)).thenReturn(new GamificationSettings(true, true, true, true, false, true));
        when(gamificationService.getGoals(CLINIC)).thenReturn(List.of());
        when(gamificationService.getThresholds(CLINIC)).thenReturn(List.of());

        controller.updateGoals(new String[]{"مهارة1", "مهارة2", "مهارة3"}, new String[]{"10", "20", "30"}, session, model);

        verify(gamificationService).updateGoal(CLINIC, 1, "مهارة1", 10);
        verify(gamificationService).updateGoal(CLINIC, 2, "مهارة2", 20);
        verify(gamificationService).updateGoal(CLINIC, 3, "مهارة3", 30);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "gamification.goals", "weekly_goal");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ الأهداف الأسبوعية");
    }

    @Test
    void updateGoalsSkipsBlankTitleRows() {
        HttpSession session = session();
        allowDashboard();
        when(gamificationService.get(CLINIC)).thenReturn(new GamificationSettings(true, true, true, true, false, true));
        when(gamificationService.getGoals(CLINIC)).thenReturn(List.of());
        when(gamificationService.getThresholds(CLINIC)).thenReturn(List.of());

        controller.updateGoals(new String[]{"مهارة1", "", ""}, new String[]{"10", "", ""}, session, model);

        verify(gamificationService).updateGoal(CLINIC, 1, "مهارة1", 10);
        verify(gamificationService, org.mockito.Mockito.never()).updateGoal(eq(CLINIC), eq(2), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(gamificationService, org.mockito.Mockito.never()).updateGoal(eq(CLINIC), eq(3), any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(model.getAttribute("goalErrors")).isEqualTo(Map.of());
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ الأهداف الأسبوعية");
    }

    @Test
    void updateThresholdsLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        when(gamificationService.get(CLINIC)).thenReturn(new GamificationSettings(true, true, true, true, false, true));
        when(gamificationService.getGoals(CLINIC)).thenReturn(List.of());
        when(gamificationService.getThresholds(CLINIC)).thenReturn(List.of());

        controller.updateThresholds(new String[]{"شارة1", "شارة2"}, new String[]{"5", "15"}, session, model);

        verify(gamificationService).updateThreshold(CLINIC, "شارة1", 5);
        verify(gamificationService).updateThreshold(CLINIC, "شارة2", 15);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "gamification.thresholds", "badge_threshold");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ شروط الشارات");
    }

    @Test
    void updateGoalsSkipsToastWhenTargetsInvalid() {
        HttpSession session = session();
        allowDashboard();

        controller.updateGoals(new String[]{"مهارة1"}, new String[]{"abc"}, session, model);

        assertThat((Map<?, ?>) model.getAttribute("goalErrors")).isNotEmpty();
        assertThat(model.getAttribute("toastMessage")).isNull();
    }

    private void allowDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(NavSectionResolver.sectionByRoute("admin-dashboard")),
                        "أحمد", "المالك", "19 مايو 2026", "admin-dashboard"));
    }

    private void denyDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "مدير", "19 مايو 2026", "admin-dashboard"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}
