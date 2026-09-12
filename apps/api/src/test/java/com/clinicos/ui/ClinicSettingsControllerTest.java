package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
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
                ClinicSettingsController.WeightsForm.from(WEIGHTS), session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: weightsCard");
        verify(settingsService).updateWeights(eq(CLINIC), any());
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void updateWeightsSurfacesServiceValidation() {
        HttpSession session = session();
        allowDashboard();
        doThrow(new ClinicSettingsValidationException(
                java.util.Map.of("weights", "مجموع أوزان مكونات التقييم يجب أن يساوي 100")))
                .when(settingsService).updateWeights(eq(CLINIC), any());

        String view = controller.updateWeights(
                ClinicSettingsController.WeightsForm.from(WEIGHTS), session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: weightsCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage")))
                .contains("مجموع أوزان مكونات التقييم يجب أن يساوي 100");
    }

    @Test
    void malformedWeightValueReportsArabicErrorAndSkipsService() {
        HttpSession session = session();
        allowDashboard();
        var form = ClinicSettingsController.WeightsForm.from(WEIGHTS);
        form.getWeights().get(0).setWeight("abc");

        String view = controller.updateWeights(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: weightsCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("أوزان مكونات التقييم غير صحيحة");
        verify(settingsService, never()).updateWeights(any(), any());
    }

    @Test
    void updateDutyParsesFieldsAndRendersCard() {
        HttpSession session = session();
        allowDashboard();
        var form = new ClinicSettingsController.DutyForm();
        form.setDefaultShiftStart("08:30");
        form.setDefaultShiftEnd("16:30");
        form.setLateGraceMinutes("20");
        form.setWorkingDaysPerMonth("22");
        form.setAcademyPassScore("65");

        String view = controller.updateDuty(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: dutyCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(settingsService).updateDuty(CLINIC, LocalTime.of(8, 30), LocalTime.of(16, 30),
                20, 22, 65);
    }

    @Test
    void badDutyNumbersReportArabicErrors() {
        HttpSession session = session();
        allowDashboard();
        var form = new ClinicSettingsController.DutyForm();
        form.setDefaultShiftStart("08:30");
        form.setDefaultShiftEnd("16:30");
        form.setLateGraceMinutes("abc");
        form.setWorkingDaysPerMonth("22");
        form.setAcademyPassScore("65");

        String view = controller.updateDuty(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: dutyCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("مهلة التأخير غير صحيحة");
        verify(settingsService, never()).updateDuty(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void updateVolumeParsesAndRendersCard() {
        HttpSession session = session();
        allowDashboard();
        var form = new ClinicSettingsController.VolumeForm();
        form.setVolumeTarget("30000");

        String view = controller.updateVolumeTarget(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: volumeCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(settingsService).updateVolumeTarget(CLINIC, new BigDecimal("30000"));
    }

    @Test
    void updateTiersCollectsIndexedRows() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateTiers(ClinicSettingsController.TiersForm.from(TIERS), session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: tiersCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(settingsService).updateTiers(eq(CLINIC), eq(List.of(
                new Tier("ممتاز", new BigDecimal("90"), new BigDecimal("100")),
                new Tier("جيد", new BigDecimal("60"), new BigDecimal("50")))));
    }

    @Test
    void tooManyTiersAreRejectedByService() {
        HttpSession session = session();
        allowDashboard();
        var form = new ClinicSettingsController.TiersForm();
        for (int i = 0; i < 21; i++) {
            form.getTiers().add(new ClinicSettingsController.TiersForm.TierRow());
        }
        doThrow(new ClinicSettingsValidationException(java.util.Map.of("tiers", "عدد شرائح الحافز يجب ألا يتجاوز 20")))
                .when(settingsService).updateTiers(eq(CLINIC), any());

        String view = controller.updateTiers(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: tiersCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
    }

    @Test
    void withoutCeoPermissionEveryCardRedirectsHome() {
        HttpSession session = session();
        denyDashboard();

        assertThat(controller.updateWeights(new ClinicSettingsController.WeightsForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateDuty(new ClinicSettingsController.DutyForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateVolumeTarget(new ClinicSettingsController.VolumeForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateTiers(new ClinicSettingsController.TiersForm(), session, model)).isEqualTo("redirect:/");
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