package com.clinicos.academy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.clinicos.shared.jooq.enums.AcademyAudience;

/**
 * The onboarding academy: role-specific curriculum (shared core + role units),
 * photo-verified practical steps, a final exam drawn from covered units, and a
 * completion certificate. Every method runs inside the clinic bound to the
 * current thread's {@code TenantContext}; callers must guarantee a tenant is
 * bound before invoking (TenantSessionFilter does for HTTP requests, tests
 * seed it explicitly).
 */
public interface AcademyService {

    /**
     * The actor making a call: membership + the session's role code + the
     * linked employee (derived by the caller from {@code EmployeeService}).
     */
    record Actor(UUID membershipId, String roleCode, UUID employeeId) {
    }

    /** A teaching section inside a unit's {@code content} jsonb. */
    record Section(String title, List<String> points) {
    }

    /** One multiple-choice question of a unit. */
    record Question(UUID id, String prompt, List<String> options, int correctIndex) {
    }

    /** A curriculum unit as visible to a trainee, with its progress state. */
    record TraineeUnit(
            UUID id,
            AcademyAudience appliesTo,
            String icon,
            String title,
            String goal,
            List<Section> content,
            String photoTask,
            boolean requiresPhoto,
            int questionCount,
            String status) {
    }

    /** A unit with its full questions — used by the editor and the learner page. */
    record Unit(
            UUID id,
            AcademyAudience appliesTo,
            String icon,
            String title,
            String goal,
            List<Section> content,
            String photoTask,
            boolean requiresPhoto,
            String status,
            List<Question> questions) {
    }

    /** One photo step submission awaiting or already reviewed by a verifier. */
    record Submission(
            UUID id,
            UUID unitId,
            String unitTitle,
            String icon,
            UUID employeeId,
            String employeeName,
            UUID photoId,
            String status,
            OffsetDateTime submittedAt,
            String rejectReason) {
    }

    /** A pending exam session: questions without their correct answers. */
    record Exam(List<ExamQuestion> questions, int totalQuestions, int passScore) {
    }

    record ExamQuestion(UUID id, String prompt, List<String> options) {
    }

    /** The immediate (auto-scored) result of an exam attempt. */
    record ExamResult(UUID attemptId, int score, int correctCount, int totalQuestions, boolean passed, int passScore) {
    }

    /** The trainee's completion certificate, derived from a passing attempt. */
    record Certificate(
            String employeeName,
            List<String> curriculumTitles,
            int passScore,
            OffsetDateTime passedAt) {
    }

    /** The learner's own curriculum: shared core units + the role's units. */
    List<TraineeUnit> myCurriculum(UUID clinicId, Actor actor);

    /** A trainee's curriculum (core + role units) as seen by a verifier. */
    TraineeTrack traineeCurriculum(UUID clinicId, Actor actor, UUID employeeId, AcademyAudience audience);

    /** Resolve an employee's academy audience from their membership role (doctor→core). */
    AcademyAudience audienceOf(UUID clinicId, UUID employeeId);

    /** A trainee's track for the learner screen: curriculum + pending submissions. */
    record TraineeTrack(UUID employeeId, String employeeName, List<TraineeUnit> units) {
    }

    /** One unit with questions, for the learner page or the editor. */
    Unit unit(UUID clinicId, UUID unitId);

    /** All non-archived units (verifier/editor view), ordered by display order. */
    List<Unit> curriculum(UUID clinicId);

    /** Submit a practical photo step for a unit. {@code photoId} from shared/AttachmentService. */
    Submission submitPhoto(UUID clinicId, Actor actor, UUID unitId, UUID photoId);

    /** Mark a non-photo unit as done (its only practical step is confirming completion). B2. */
    TraineeUnit markDone(UUID clinicId, Actor actor, UUID unitId);

    /** All currently pending photo submissions (for the verifier queue). */
    List<Submission> pendingSubmissions(UUID clinicId);

    /** Confirm a pending submission (verifier, owner/manager). */
    Submission verify(UUID clinicId, Actor actor, UUID submissionId);

    /** Reject a pending submission with a reason (verifier, owner/manager). A1. */
    Submission reject(UUID clinicId, Actor actor, UUID submissionId, String reason);

    /** Build an exam from the covered units; the unit list is the certificate's curriculum. */
    Exam exam(UUID clinicId, Actor actor);

    /** Score an exam immediately; {@code answers} maps question id → selected option index. */
    ExamResult submitExam(UUID clinicId, Actor actor, Map<UUID, Integer> answers);

    /** Latest passing attempt's certificate, if any. */
    Certificate certificate(UUID clinicId, Actor actor);

    /** Import the platform default curriculum (nine catalog units) into the clinic. A3. */
    void importDefaultCurriculum(UUID clinicId, Actor actor);

    /** Save a unit (title/goal/icon/photo/requires-photo/content) and its questions. A3. */
    Unit saveUnit(UUID clinicId, Actor actor, UnitRequest request);

    record UnitRequest(
            UUID id,
            AcademyAudience appliesTo,
            String icon,
            String title,
            String goal,
            List<Section> content,
            String photoTask,
            boolean requiresPhoto,
            List<QuestionRequest> questions) {
    }

    record QuestionRequest(UUID id, String prompt, List<String> options, int correctIndex, int displayOrder) {
    }
}
