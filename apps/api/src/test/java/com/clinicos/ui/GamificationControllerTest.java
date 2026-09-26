package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
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
        assertThat(model.getAttribute("goalForm")).isNotNull();
        assertThat(model.getAttribute("thresholdForm")).isNotNull();
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

        verify(gamificationService).updateSettings(eq(CLINIC), any(GamificationSettings.class), eq(MEMBERSHIP));
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

        var form = GamificationController.GoalsForm.from(List.of());
        goals(form).setTitle("مهارة1");
        goals(form).setTarget("10");
        targetsOf(form, 1).setTitle("مهارة2");
        targetsOf(form, 1).setTarget("20");
        targetsOf(form, 2).setTitle("مهارة3");
        targetsOf(form, 2).setTarget("30");

        controller.updateGoals(form, session, model);

        verify(gamificationService).updateGoal(CLINIC, 1, "مهارة1", 10, MEMBERSHIP);
        verify(gamificationService).updateGoal(CLINIC, 2, "مهارة2", 20, MEMBERSHIP);
        verify(gamificationService).updateGoal(CLINIC, 3, "مهارة3", 30, MEMBERSHIP);
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

        var form = GamificationController.GoalsForm.from(List.of());
        goals(form).setTitle("مهارة1");
        goals(form).setTarget("10");

        controller.updateGoals(form, session, model);

        verify(gamificationService).updateGoal(CLINIC, 1, "مهارة1", 10, MEMBERSHIP);
        verify(gamificationService, never()).updateGoal(eq(CLINIC), eq(2), any(), anyInt(), any());
        verify(gamificationService, never()).updateGoal(eq(CLINIC), eq(3), any(), anyInt(), any());
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ الأهداف الأسبوعية");
    }

    @Test
    void updateThresholdsLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        when(gamificationService.get(CLINIC)).thenReturn(new GamificationSettings(true, true, true, true, false, true));
        when(gamificationService.getGoals(CLINIC)).thenReturn(List.of());
        when(gamificationService.getThresholds(CLINIC)).thenReturn(List.of());

        var form = new GamificationController.ThresholdsForm();
        threshold(form, "شارة1").setThreshold("5");
        threshold(form, "شارة2").setThreshold("15");

        controller.updateThresholds(form, session, model);

        verify(gamificationService).updateThreshold(CLINIC, "شارة1", 5, MEMBERSHIP);
        verify(gamificationService).updateThreshold(CLINIC, "شارة2", 15, MEMBERSHIP);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "gamification.thresholds", "badge_threshold");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ شروط الشارات");
    }

    @Test
    void updateGoalsInvalidTargetReportsErrorAndSkipsService() {
        HttpSession session = session();
        allowDashboard();

        var form = GamificationController.GoalsForm.from(List.of());
        goals(form).setTitle("مهارة1");
        goals(form).setTarget("abc");

        controller.updateGoals(form, session, model);

        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("قيمة الهدف يجب أن تكون رقماً");
        verify(gamificationService, never()).updateGoal(any(), anyInt(), any(), anyInt(), any());
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void updateGoalsRejectsMoreThanThreeRows() {
        HttpSession session = session();
        allowDashboard();

        var form = GamificationController.GoalsForm.from(List.of());
        goals(form).setTitle("مهارة1");
        form.getGoals().add(new GamificationController.GoalsForm.GoalRow());

        controller.updateGoals(form, session, model);

        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("لا يمكن حفظ أكثر من 3 أهداف");
        verify(gamificationService, never()).updateGoal(any(), anyInt(), any(), anyInt(), any());
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void updateThresholdsInvalidValueReportsErrorAndSkipsService() {
        HttpSession session = session();
        allowDashboard();

        var form = new GamificationController.ThresholdsForm();
        threshold(form, "شارة1").setThreshold("abc");

        controller.updateThresholds(form, session, model);

        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("عدد المهام يجب أن يكون رقماً");
        verify(gamificationService, never()).updateThreshold(any(), any(), anyInt(), any());
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    private static GamificationController.GoalsForm.GoalRow goals(GamificationController.GoalsForm form) {
        return form.getGoals().get(0);
    }

    private static GamificationController.GoalsForm.GoalRow targetsOf(GamificationController.GoalsForm form, int index) {
        return form.getGoals().get(index);
    }

    private static GamificationController.ThresholdsForm.ThresholdRow threshold(
            GamificationController.ThresholdsForm form, String name) {
        GamificationController.ThresholdsForm.ThresholdRow row =
                new GamificationController.ThresholdsForm.ThresholdRow();
        row.setName(name);
        form.getThresholds().add(row);
        return row;
    }

    private void allowDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(NavSectionResolver.sectionByRoute("admin-dashboard")),
                        "أحمد", "عيادتي", "المالك", "19 مايو 2026", "admin-dashboard"));
    }

    private void denyDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "عيادتي", "مدير", "19 مايو 2026", "admin-dashboard"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}