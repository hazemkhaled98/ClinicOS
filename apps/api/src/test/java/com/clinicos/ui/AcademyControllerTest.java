package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.academy.AcademyService;
import com.clinicos.academy.AcademyService.TraineeUnit;
import com.clinicos.academy.AcademyService.Unit;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.AttachmentService;
import com.clinicos.shared.jooq.enums.AcademyAudience;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;

import jakarta.servlet.http.HttpSession;

@Tag("UC-007")
class AcademyControllerTest {
    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID EMPLOYEE = UUID.randomUUID();

    private LayoutModel layoutModel;
    private AcademyService academyService;
    private EmployeeService employeeService;
    private AcademyController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        academyService = mock(AcademyService.class);
        employeeService = mock(EmployeeService.class);
        controller = new AcademyController(layoutModel, academyService, employeeService,
                mock(AttachmentService.class), mock(ActivityLogService.class));
        model = new ExtendedModelMap();
    }

    @Test
    void UC007_anonymousAcademyRequestRedirectsToLogin() {
        HttpSession session = mock(HttpSession.class);

        assertThat(controller.index(session, model)).isEqualTo("redirect:/login");
        verify(employeeService, never()).list(any());
    }

    @Test
    void UC007_authenticatedTraineeCanOpenAcademy() {
        when(employeeService.list(CLINIC)).thenReturn(List.of(employee()));
        when(academyService.audienceOf(CLINIC, EMPLOYEE)).thenReturn(
                com.clinicos.shared.jooq.enums.AcademyAudience.assistant);

        assertThat(controller.index(session(), model)).isEqualTo("academy");
        assertThat(model.getAttribute("employees")).isEqualTo(List.of(employee()));
    }

    @Test
    void UC007_authenticatedTraineeCanOpenOwnCurriculum() {
        var unit = new TraineeUnit(UUID.randomUUID(), AcademyAudience.assistant, "📘", "عنوان", "هدف",
                List.of(), "مهمة", true, 0, "open");
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(employee());
        when(academyService.myCurriculum(any(), any())).thenReturn(List.of(unit));

        assertThat(controller.myLearning(session(), model)).isEqualTo("academy-learner");
        var track = (AcademyService.TraineeTrack) model.getAttribute("track");
        assertThat(track.units()).extracting(TraineeUnit::requiresPhoto).containsExactly(true);
    }

    @Test
    void UC007_ownerWithoutEmployeeRedirectsFromOwnCurriculum() {
        when(employeeService.findByMembership(CLINIC, MEMBERSHIP)).thenReturn(null);

        assertThat(controller.myLearning(session("owner"), model)).isEqualTo("redirect:/academy");
    }

    @Test
    void UC007_crossTenantLearnerRedirectsToAcademy() {
        UUID otherClinicEmployee = UUID.randomUUID();
        when(academyService.traineeCurriculum(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("الموظف غير موجود"));

        assertThat(controller.learner(otherClinicEmployee, session("manager"), model))
                .isEqualTo("redirect:/academy");
    }

    @Test
    void UC007_traineeCannotOpenVerifierQueue() {
        assertThat(controller.verifyQueue(session(), model)).isEqualTo("redirect:/academy");
        verify(academyService, never()).pendingSubmissions(any());
    }

    @Test
    void UC007_verifierCanOpenQueue() {
        when(academyService.pendingSubmissions(CLINIC)).thenReturn(List.of());

        assertThat(controller.verifyQueue(session("manager"), model)).isEqualTo("academy-verify");
    }

    @Test
    void UC007_traineeCannotVerifyOrRejectSubmissions() {
        UUID submissionId = UUID.randomUUID();

        assertThat(controller.verify(submissionId, session(), model)).isEqualTo("redirect:/academy");
        assertThat(controller.reject(submissionId, "سبب", session(), model)).isEqualTo("redirect:/academy");
        verify(academyService, never()).verify(any(), any(), any());
        verify(academyService, never()).reject(any(), any(), any(), any());
        verify(academyService, never()).pendingSubmissions(any());
    }

    @Test
    void UC007_traineeCannotOpenCurriculumEditorRoutes() {
        UUID unitId = UUID.randomUUID();

        assertThat(controller.editUnit(unitId, session(), model)).isEqualTo("redirect:/academy");
        assertThat(controller.newUnit(session(), model)).isEqualTo("redirect:/academy");
        assertThat(controller.curriculum(session(), model)).isEqualTo("redirect:/academy");
        verify(academyService, never()).unit(any(), any());
        verify(academyService, never()).curriculum(any());
    }

    @Test
    void UC007_editorCanOpenCurriculumEditorRoutes() {
        UUID unitId = UUID.randomUUID();
        HttpSession session = session("manager");
        when(academyService.unit(CLINIC, unitId)).thenReturn(unit());
        when(academyService.curriculum(CLINIC)).thenReturn(List.of());

        assertThat(controller.curriculum(session, model)).isEqualTo("academy-curriculum");
        assertThat(controller.newUnit(session, model)).isEqualTo("academy-unit-editor");
        assertThat(controller.editUnit(unitId, session, model)).isEqualTo("academy-unit-editor");
        verify(academyService).unit(CLINIC, unitId);
    }

    @Test
    void UC007_editorTemplateUsesBeanPropertyForUnitId() throws Exception {
        try (var template = getClass().getResourceAsStream("/templates/academy-unit-editor.html")) {
            assertThat(template).isNotNull();
            assertThat(new String(template.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("th:text=\"${form.id != null}");
        }
    }


    private static HttpSession session() {
        return session("assistant");
    }

    private static HttpSession session(String role) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn(role);
        return session;
    }

    private static Employee employee() {
        return new Employee(EMPLOYEE, "مساعد", BigDecimal.ZERO, BigDecimal.ZERO,
                LocalTime.MIN, LocalTime.MAX, true, LocalDate.now(), null);
    }

    private static Unit unit() {
        return new Unit(UUID.randomUUID(), AcademyAudience.assistant, "📘", "وحدة",
                "هدف", List.of(), "مهمة", true, "open", List.of());
    }
}
