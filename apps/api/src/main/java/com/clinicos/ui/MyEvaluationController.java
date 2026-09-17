package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.clinicconfig.api.GamificationService;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
import com.clinicos.evaluation.api.EvaluationService;
import com.clinicos.evaluation.api.EvaluationService.ComponentScore;
import com.clinicos.evaluation.api.EvaluationService.EvaluationConflictException;
import com.clinicos.evaluation.api.EvaluationService.Gamification;
import com.clinicos.evaluation.api.EvaluationService.MonthlyEvaluation;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

@Controller
public class MyEvaluationController {

    private final LayoutModel layoutModel;
    private final EmployeeService employeeService;
    private final EvaluationService evaluationService;
    private final GamificationService gamificationService;

    public MyEvaluationController(LayoutModel layoutModel, EmployeeService employeeService,
            EvaluationService evaluationService, GamificationService gamificationService) {
        this.layoutModel = layoutModel;
        this.employeeService = employeeService;
        this.evaluationService = evaluationService;
        this.gamificationService = gamificationService;
    }

    @GetMapping("/my-evaluation")
    public String myEvaluation(@RequestParam(required = false) String month, HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        UUID membershipId = AdminAccess.membershipId(session);
        Employee employee = employeeService.findByMembership(clinicId, membershipId);
        if (employee == null) {
            return "redirect:/";
        }
        YearMonth selectedMonth = parseMonth(month);

        model.addAttribute("layout", layoutModel.forRequest(session, "my-evaluation"));
        model.addAttribute("employeeName", employee.name());
        model.addAttribute("month", selectedMonth.toString());
        model.addAttribute("currentMonth", YearMonth.now().toString());
        model.addAttribute("prevMonth", selectedMonth.minusMonths(1).toString());
        model.addAttribute("nextMonth", selectedMonth.plusMonths(1).toString());
        model.addAttribute("nextDisabled", selectedMonth.equals(YearMonth.now()));
        model.addAttribute("isCurrentMonth", selectedMonth.equals(YearMonth.now()));

        MyEvaluationView view = buildView(clinicId, employee.id(), selectedMonth);
        model.addAttribute("view", view);
        model.addAttribute("hasData", view != null && view.daysLogged() > 0);

        GamificationSettings settings = gamificationService.get(clinicId);
        model.addAttribute("gamificationSettings", settings);
        if (settings != null) {
            Gamification gamification = evaluationService.gamification(clinicId, employee.id(), selectedMonth);
            List<GoalView> goals = gamification.goals().stream()
                    .map(g -> new GoalView(g.title(), g.target(), g.current(),
                            g.target() > 0 ? Math.min(100, g.current() * 100 / g.target()) : 0))
                    .toList();
            model.addAttribute("goals", goals);
            model.addAttribute("streak", gamification.streak());
            model.addAttribute("earnedBadges", gamification.earnedBadges());
        }
        return "my-evaluation";
    }

    private boolean canView(HttpSession session) {
        return layoutModel.forRequest(session, "my-evaluation")
                .nav()
                .contains(NavSectionResolver.sectionByRoute("my-evaluation"));
    }

    private MyEvaluationView buildView(UUID clinicId, UUID employeeId, YearMonth month) {
        try {
            MonthlyEvaluation ev = evaluationService.evaluate(clinicId, employeeId, month);
            if (ev == null) {
                return null;
            }
            List<ComponentView> components = ev.components().stream()
                    .map(c -> new ComponentView(c.category().code(), c.category().arabicName(),
                            c.rawScore(), c.weight(), c.included()))
                    .toList();
            List<String> coverageGaps = ev.components().stream()
                    .filter(c -> !c.included())
                    .map(c -> c.category().arabicName())
                    .toList();
            return new MyEvaluationView(ev.finalScore(), ev.coverage(), ev.tierName(),
                    ev.incentiveAmount(), ev.totalPay(), ev.daysLogged(), ev.frozen(), components, coverageGaps);
        } catch (EvaluationConflictException e) {
            return null;
        }
    }

    private static YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return YearMonth.now();
        }
    }

    record MyEvaluationView(BigDecimal finalScore, BigDecimal coverage, String tierName,
            BigDecimal incentiveAmount, BigDecimal totalPay, int daysLogged, boolean frozen,
            List<ComponentView> components, List<String> coverageGaps) {
    }

    record ComponentView(String code, String name, BigDecimal rawScore, BigDecimal weight, boolean included) {
    }

    record GoalView(String title, int target, int current, int percent) {
    }
}
