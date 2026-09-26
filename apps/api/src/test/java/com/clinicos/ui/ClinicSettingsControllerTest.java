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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpSession;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.ClinicSettingsService.CategoryWeight;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettings;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettingsValidationException;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Tier;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.clinicconfig.api.WorkCalendarService.HolidayRequest;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class ClinicSettingsControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
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
    private WorkCalendarService workCalendarService;
    private EmployeeService employeeService;
    private ClinicSettingsController controller;
    private MockMvc mockMvc;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        settingsService = mock(ClinicSettingsService.class);
        workCalendarService = mock(WorkCalendarService.class);
        employeeService = mock(EmployeeService.class);
        controller = new ClinicSettingsController(layoutModel, settingsService,
                workCalendarService, employeeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        model = new ExtendedModelMap();
        when(settingsService.get(CLINIC)).thenReturn(settings(WEIGHTS, TIERS));
        when(workCalendarService.workingWeekdays(CLINIC)).thenReturn(List.of(6, 7, 1, 2, 3, 4));
        when(workCalendarService.listHolidays(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());
    }

    @Test
    void updateWeightsRendersCardAndAppliesChange() {
        HttpSession session = session();
        allowDashboard();

        String view = controller.updateWeights(
                ClinicSettingsController.WeightsForm.from(WEIGHTS), session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: weightsCard");
        verify(settingsService).updateWeights(eq(CLINIC), any(), eq(ACTOR));
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void updateWeightsSurfacesServiceValidation() {
        HttpSession session = session();
        allowDashboard();
        doThrow(new ClinicSettingsValidationException(
                java.util.Map.of("weights", "مجموع أوزان مكونات التقييم يجب أن يساوي 100")))
                .when(settingsService).updateWeights(eq(CLINIC), any(), eq(ACTOR));

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
        verify(settingsService, never()).updateWeights(any(), any(), any());
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
                20, 22, 65, ACTOR);
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
        verify(settingsService, never()).updateDuty(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
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
        verify(settingsService).updateVolumeTarget(CLINIC, new BigDecimal("30000"), ACTOR);
    }

    @Test
    void invoicePhotoPolicyDefaultsMissingRequestParameterToFalse() throws Exception {
        allowDashboard();

        mockMvc.perform(post("/admin-dashboard/settings/invoice-photo-policy")
                .session(httpSession()))
                .andExpect(status().isOk());

        verify(settingsService).updateInvoicePhotoRequired(CLINIC, false, ACTOR);
    }

    @Test
    void invoicePhotoPolicyBindsTrueRequestParameter() throws Exception {
        allowDashboard();

        mockMvc.perform(post("/admin-dashboard/settings/invoice-photo-policy")
                .session(httpSession())
                .param("invoicePhotoRequired", "true"))
                .andExpect(status().isOk());

        verify(settingsService).updateInvoicePhotoRequired(CLINIC, true, ACTOR);
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
                new Tier("جيد", new BigDecimal("60"), new BigDecimal("50")))), eq(ACTOR));
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
                .when(settingsService).updateTiers(eq(CLINIC), any(), eq(ACTOR));

        String view = controller.updateTiers(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: tiersCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
    }

    @Test
    void updateWeekdaysPersistsMaskAndRendersCard() {
        HttpSession session = session();
        allowDashboard();
        var form = new ClinicSettingsController.WeekdaysForm();
        form.setWeekdays(List.of(1, 2, 3, 4, 5, 6, 7));

        String view = controller.updateWeekdays(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: calendarCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(workCalendarService).setWorkingWeekdays(CLINIC, List.of(1, 2, 3, 4, 5, 6, 7), ACTOR);
        verify(workCalendarService).listHolidays(CLINIC);
        verify(employeeService).list(CLINIC);
    }

    @Test
    void emptyWeekdaySetReportsArabicError() {
        HttpSession session = session();
        allowDashboard();
        doThrow(new IllegalArgumentException("أيام العمل يجب أن تتضمن يوماً واحداً على الأقل"))
                .when(workCalendarService).setWorkingWeekdays(eq(CLINIC), any(), eq(ACTOR));
        var form = new ClinicSettingsController.WeekdaysForm();

        String view = controller.updateWeekdays(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: calendarCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage")))
                .contains("أيام العمل يجب أن تتضمن يوماً واحداً على الأقل");
    }

    @Test
    void addHolidayRendersCardAndInvokesService() {
        HttpSession session = session();
        allowDashboard();
        var form = new ClinicSettingsController.HolidayForm();
        form.setDate("2026-03-20");
        form.setName("عيد الفطر");

        String view = controller.addHoliday(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: calendarCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(workCalendarService).addHoliday(eq(CLINIC),
                any(HolidayRequest.class), eq(ACTOR));
    }

    @Test
    void blankHolidayDateAndNameReportArabicFieldErrors() {
        HttpSession session = session();
        allowDashboard();
        var form = new ClinicSettingsController.HolidayForm();

        String view = controller.addHoliday(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: calendarCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage")))
                .contains("تاريخ الإجازة غير صحيح", "اسم الإجازة مطلوب");
        verify(workCalendarService, never()).addHoliday(any(), any(), any());
    }

    @Test
    void duplicateHolidaySurfacesArabicServiceError() {
        HttpSession session = session();
        allowDashboard();
        doThrow(new IllegalArgumentException("هذه الإجازة مسجلة مسبقاً"))
                .when(workCalendarService).addHoliday(eq(CLINIC), any(), eq(ACTOR));
        var form = new ClinicSettingsController.HolidayForm();
        form.setDate("2026-03-20");
        form.setName("عيد الفطر");

        String view = controller.addHoliday(form, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: calendarCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("مسجلة مسبقاً");
        assertThat(((ClinicSettingsController.HolidayForm) model.getAttribute("holidaysForm")).getName())
                .isEqualTo("عيد الفطر");
    }

    @Test
    void deleteHolidayInvokesServiceAndRendersCard() {
        HttpSession session = session();
        allowDashboard();
        UUID holidayId = UUID.randomUUID();

        String view = controller.deleteHoliday(holidayId, session, model);

        assertThat(view).isEqualTo("admin/clinic-settings :: calendarCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(workCalendarService).removeHoliday(CLINIC, holidayId, ACTOR);
    }

    @Test
    void withoutCeoPermissionEveryCardRedirectsHome() {
        HttpSession session = session();
        denyDashboard();

        assertThat(controller.updateWeights(new ClinicSettingsController.WeightsForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateDuty(new ClinicSettingsController.DutyForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateVolumeTarget(new ClinicSettingsController.VolumeForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateTiers(new ClinicSettingsController.TiersForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.updateWeekdays(new ClinicSettingsController.WeekdaysForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.addHoliday(new ClinicSettingsController.HolidayForm(), session, model)).isEqualTo("redirect:/");
        assertThat(controller.deleteHoliday(UUID.randomUUID(), session, model)).isEqualTo("redirect:/");
        verify(settingsService, never()).updateWeights(any(), any(), any());
        verify(settingsService, never()).updateDuty(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        verify(settingsService, never()).updateVolumeTarget(any(), any(), any());
        verify(settingsService, never()).updateTiers(any(), any(), any());
        verify(workCalendarService, never()).setWorkingWeekdays(any(), any(), any());
        verify(workCalendarService, never()).addHoliday(any(), any(), any());
        verify(workCalendarService, never()).removeHoliday(any(), any(), any());
    }

    @Test
    void calendarHandlersRenderHolidayListAndEmployeeSelect() {
        HttpSession session = session();
        allowDashboard();
        Employee employee = new Employee(UUID.randomUUID(), "محمود", null, null, null, null, false, null, null);
        when(employeeService.list(CLINIC)).thenReturn(List.of(employee));

        controller.updateWeekdays(new ClinicSettingsController.WeekdaysForm(), session, model);

        assertThat(model.getAttribute("employees")).isEqualTo(List.of(employee));
        assertThat(model.getAttribute("holidays")).isEqualTo(List.of());
    }

    private static ClinicSettings settings(List<CategoryWeight> weights, List<Tier> tiers) {
        return new ClinicSettings(LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 26,
                new BigDecimal("20000"), 70, weights, tiers, true);
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
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(ACTOR);
        return session;
    }

    private static MockHttpSession httpSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.CLINIC_ID, CLINIC);
        session.setAttribute(SessionKeys.MEMBERSHIP_ID, ACTOR);
        return session;
    }
}
