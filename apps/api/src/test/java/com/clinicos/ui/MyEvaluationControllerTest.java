package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
import com.clinicos.evaluation.api.EvaluationService;
import com.clinicos.evaluation.api.EvaluationService.ComponentScore;
import com.clinicos.evaluation.api.EvaluationService.EvaluationConflictException;
import com.clinicos.evaluation.api.EvaluationService.Gamification;
import com.clinicos.evaluation.api.EvaluationService.GoalProgress;
import com.clinicos.evaluation.api.EvaluationService.MonthlyEvaluation;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;

import jakarta.servlet.http.HttpSession;

class MyEvaluationControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();

    private LayoutModel layoutModel;
    private EmployeeService employeeService;
    private EvaluationService evaluationService;
    private GamificationService gamificationService;
    private MyEvaluationController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        employeeService = mock(EmployeeService.class);
        evaluationService = mock(EvaluationService.class);
        gamificationService = mock(GamificationService.class);
        controller = new MyEvaluationController(layoutModel, employeeService, evaluationService, gamificationService);
        model = new ExtendedModelMap();
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(new Employee(
                EMPLOYEE_ID, "أحمد", new BigDecimal("5000"), new BigDecimal("500"), null, null, false, null, null));
        when(gamificationService.get(CLINIC)).thenReturn(null);
    }

    @Test
    void redirectsHomeWithoutPermission() {
        denyView();

        String view = controller.myEvaluation(null, session(), model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void redirectsOwnerWithNoEmployeeRecord() {
        allowView();
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(null);

        String view = controller.myEvaluation(null, session(), model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void showsEmptyStateWhenNoDaysLogged() {
        allowView();
        MonthlyEvaluation ev = new MonthlyEvaluation(null, null, List.of(), null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
        when(evaluationService.evaluate(CLINIC, EMPLOYEE_ID, YearMonth.now())).thenReturn(ev);

        String view = controller.myEvaluation(null, session(), model);

        assertThat(view).isEqualTo("my-evaluation");
        assertThat(model.getAttribute("hasData")).isEqualTo(false);
    }

    @Test
    void showsOwnEvaluationOnly() {
        allowView();
        MonthlyEvaluation ev = new MonthlyEvaluation(
                new BigDecimal("68.57"), new BigDecimal("0.70"), List.of(), "جيد",
                new BigDecimal("500.00"), new BigDecimal("5000.00"), new BigDecimal("5500.00"),
                20, false);
        when(evaluationService.evaluate(CLINIC, EMPLOYEE_ID, YearMonth.now())).thenReturn(ev);

        String view = controller.myEvaluation(null, session(), model);

        assertThat(view).isEqualTo("my-evaluation");
        assertThat(model.getAttribute("hasData")).isEqualTo(true);
        assertThat(model.getAttribute("employeeName")).isEqualTo("أحمد");
        assertThat(model.getAttribute("view")).isNotNull();
    }

    @Test
    void showsCoverageGapsForExcludedComponents() {
        allowView();
        MonthlyEvaluation ev = new MonthlyEvaluation(
                new BigDecimal("70"), new BigDecimal("0.83"),
                List.of(
                        new ComponentScore(Category.COMPLETION, new BigDecimal("90"), new BigDecimal("1"), true, null),
                        new ComponentScore(Category.VOLUME, null, new BigDecimal("1"), false, null)),
                "جيد", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 20, false);
        when(evaluationService.evaluate(CLINIC, EMPLOYEE_ID, YearMonth.now())).thenReturn(ev);

        String view = controller.myEvaluation(null, session(), model);

        assertThat(view).isEqualTo("my-evaluation");
        MyEvaluationController.MyEvaluationView data =
                (MyEvaluationController.MyEvaluationView) model.getAttribute("view");
        assertThat(data.components()).hasSize(2);
        assertThat(data.coverageGaps()).containsExactly("حجم الإنتاج");
    }

    @Test
    void unlockConflictShowsNoData() {
        allowView();
        when(evaluationService.evaluate(any(), any(), any()))
                .thenThrow(new EvaluationConflictException("الشهر مقفل"));

        String view = controller.myEvaluation(null, session(), model);

        assertThat(view).isEqualTo("my-evaluation");
        assertThat(model.getAttribute("hasData")).isEqualTo(false);
    }

    @Test
    void exposesGamificationWhenSettingsPresent() {
        allowView();
        MonthlyEvaluation ev = new MonthlyEvaluation(
                new BigDecimal("70"), new BigDecimal("1"), List.of(), "جيد",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 15, false);
        when(evaluationService.evaluate(CLINIC, EMPLOYEE_ID, YearMonth.now())).thenReturn(ev);
        when(gamificationService.get(CLINIC)).thenReturn(
                new GamificationSettings(true, true, true, true, false, false));
        when(evaluationService.gamification(CLINIC, EMPLOYEE_ID, YearMonth.now())).thenReturn(
                new Gamification(List.of(new GoalProgress("إنجاز أسبوعي", 5, 3)), 4, List.of("نجم الأسبوع")));

        String view = controller.myEvaluation(null, session(), model);

        assertThat(view).isEqualTo("my-evaluation");
        assertThat(model.getAttribute("streak")).isEqualTo(4);
        assertThat((List<String>) model.getAttribute("earnedBadges")).contains("نجم الأسبوع");
    }

    private void allowView() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("my-evaluation")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(com.clinicos.ui.nav.NavSectionResolver.sectionByRoute("my-evaluation")),
                        "أحمد", "موظف", "19 مايو 2026", "my-evaluation"));
    }

    private void denyView() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("my-evaluation")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "مالك", "19 مايو 2026", "my-evaluation"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}
