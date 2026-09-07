package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.ClinicSettingsService.CategoryWeight;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettings;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettingsValidationException;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Tier;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class ClinicSettingsControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final List<CategoryWeight> WEIGHTS = List.of(
            new CategoryWeight(Category.COMPLETION, new BigDecimal("18")),
            new CategoryWeight(Category.FANNI, new BigDecimal("18")),
            new CategoryWeight(Category.SOLOOKI, new BigDecimal("12")),
            new CategoryWeight(Category.IBDA3, new BigDecimal("22")),
            new CategoryWeight(Category.ATTENDANCE, new BigDecimal("12")),
            new CategoryWeight(Category.VOLUME, new BigDecimal("18")));
    private static final List<Tier> TIERS = List.of(
            new Tier("ممتاز", new BigDecimal("90"), new BigDecimal("100")),
            new Tier("جيد", new BigDecimal("60"), new BigDecimal("50")));

    private LayoutModel layoutModel;
    private ClinicSettingsService settingsService;
    private ClinicSettingsController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        settingsService = mock(ClinicSettingsService.class);
        controller = new ClinicSettingsController(layoutModel, settingsService);
        model = new ExtendedModelMap();
        when(settingsService.get(CLINIC)).thenReturn(settings(WEIGHTS, TIERS));
    }

    @Test
    void updateWeightsRendersCardAndAppliesChange() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateWeights(
                Map.of("weight-completion", "20", "weight-fanni", "16",
                        "weight-solooki", "12", "weight-ibda3", "22",
                        "weight-attendance", "12", "weight-volume", "18"),
                session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: weightsCard");
        verify(settingsService).updateWeights(eq(CLINIC), any());
        assertThat(model.getAttribute("weightErrors")).isNull();
    }

    @Test
    void updateWeightsSurfacesServiceValidation() {
        HttpSession session = session();
        allowDashboard();
        org.mockito.Mockito.doThrow(new ClinicSettingsValidationException(
                        Map.of("weights", "مجموع أوزان مكونات التقييم يجب أن يساوي 100")))
                .when(settingsService).updateWeights(eq(CLINIC), any());

        String view = controller.updateWeights(Map.of(
                "weight-completion", "10", "weight-fanni", "18", "weight-solooki", "12",
                "weight-ibda3", "22", "weight-attendance", "12", "weight-volume", "18"),
                session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: weightsCard");
        assertThat(model.getAttribute("weightErrors"))
                .isEqualTo(Map.of("weights", "مجموع أوزان مكونات التقييم يجب أن يساوي 100"));
    }

    @Test
    void malformedWeightValueReportsArabicErrorAndSkipsService() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateWeights(Map.of(
                "weight-completion", "abc", "weight-fanni", "18", "weight-solooki", "12",
                "weight-ibda3", "22", "weight-attendance", "12", "weight-volume", "18"),
                session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: weightsCard");
        assertThat(model.getAttribute("weightErrors"))
                .isEqualTo(Map.of("weights", "أوزان مكونات التقييم غير صحيحة"));
        verify(settingsService, never()).updateWeights(any(), any());
    }

    @Test
    void updateDutyParsesFieldsAndRendersCard() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateDuty(Map.of(
                "defaultShiftStart", "08:30", "defaultShiftEnd", "16:30",
                "lateGraceMinutes", "20", "workingDaysPerMonth", "22", "academyPassScore", "65"),
                session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: dutyCard");
        verify(settingsService).updateDuty(CLINIC, LocalTime.of(8, 30), LocalTime.of(16, 30),
                20, 22, 65);
    }

    @Test
    void badDutyNumbersReportArabicErrors() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateDuty(Map.of(
                "defaultShiftStart", "08:30", "defaultShiftEnd", "16:30",
                "lateGraceMinutes", "abc", "workingDaysPerMonth", "22", "academyPassScore", "65"),
                session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: dutyCard");
        assertThat(model.getAttribute("dutyErrors"))
                .isEqualTo(Map.of("lateGraceMinutes", "مهلة التأخير غير صحيحة"));
        verify(settingsService, never()).updateDuty(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void updateVolumeParsesAndRendersCard() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateVolumeTarget(Map.of("volumeTarget", "30000"), session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: volumeCard");
        verify(settingsService).updateVolumeTarget(CLINIC, new BigDecimal("30000"));
    }

    @Test
    void updateTiersCollectsIndexedRows() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateTiers(Map.of(
                "tierName0", "ممتاز", "tierMinScore0", "90", "tierPct0", "100",
                "tierName1", "جيد", "tierMinScore1", "60", "tierPct1", "50"),
                session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: tiersCard");
        verify(settingsService).updateTiers(eq(CLINIC), eq(List.of(
                new Tier("ممتاز", new BigDecimal("90"), new BigDecimal("100")),
                new Tier("جيد", new BigDecimal("60"), new BigDecimal("50")))));
    }

    @Test
    void withoutCeoPermissionEveryCardRedirectsHome() {
        HttpSession session = session();
        denyDashboard();

        assertThat(controller.updateWeights(Map.of(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateDuty(Map.of(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateVolumeTarget(Map.of(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateTiers(Map.of(), session, model)).isEqualTo("redirect:/");
        verify(settingsService, never()).updateWeights(any(), any());
        verify(settingsService, never()).updateDuty(any(), any(), any(), anyInt(), anyInt(), anyInt());
        verify(settingsService, never()).updateVolumeTarget(any(), any());
        verify(settingsService, never()).updateTiers(any(), any());
    }

    private static ClinicSettings settings(List<CategoryWeight> weights, List<Tier> tiers) {
        return new ClinicSettings(LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 26,
                new BigDecimal("20000"), 70, weights, tiers);
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
        return session;
    }
}