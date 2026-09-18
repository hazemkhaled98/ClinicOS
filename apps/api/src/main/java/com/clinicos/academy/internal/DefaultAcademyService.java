package com.clinicos.academy.internal;

import static com.clinicos.shared.jooq.tables.AcademyUnit.ACADEMY_UNIT;
import static com.clinicos.shared.jooq.tables.AcademyQuestion.ACADEMY_QUESTION;
import static com.clinicos.shared.jooq.tables.AcademyStepSubmission.ACADEMY_STEP_SUBMISSION;
import static com.clinicos.shared.jooq.tables.AcademyExamAttempt.ACADEMY_EXAM_ATTEMPT;
import static com.clinicos.shared.jooq.tables.AcademyTemplateUnit.ACADEMY_TEMPLATE_UNIT;
import static com.clinicos.shared.jooq.tables.AcademyTemplateQuestion.ACADEMY_TEMPLATE_QUESTION;
import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.academy.AcademyService;
import com.clinicos.academy.AcademyService.Actor;
import com.clinicos.academy.AcademyService.Certificate;
import com.clinicos.academy.AcademyService.Exam;
import com.clinicos.academy.AcademyService.ExamQuestion;
import com.clinicos.academy.AcademyService.ExamResult;
import com.clinicos.academy.AcademyService.Question;
import com.clinicos.academy.AcademyService.QuestionRequest;
import com.clinicos.academy.AcademyService.Section;
import com.clinicos.academy.AcademyService.Submission;
import com.clinicos.academy.AcademyService.TraineeUnit;
import com.clinicos.academy.AcademyService.Unit;
import com.clinicos.academy.AcademyService.UnitRequest;
import com.clinicos.shared.jooq.enums.AcademyAudience;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.enums.SubmissionStatus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DefaultAcademyService implements AcademyService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Section>> SECTIONS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> OPTIONS_TYPE = new TypeReference<>() {};

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultAcademyService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<TraineeUnit> myCurriculum(UUID clinicId, Actor actor) {
        return transactionTemplate.execute(tx -> {
            requireEmployee(clinicId, actor);
            return requiredCurriculum(clinicId, actor);
        });
    }

    @Override
    public TraineeTrack traineeCurriculum(UUID clinicId, Actor actor, UUID employeeId, AcademyAudience audience) {
        return transactionTemplate.execute(status -> {
            requireVerifier(clinicId, actor);
            var emp = dsl.selectFrom(EMPLOYEE).where(EMPLOYEE.ID.eq(employeeId)).fetchOne();
            if (emp == null || !dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP)
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId)).and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId)))) {
                throw missing("الموظف غير موجود");
            }
            return new TraineeTrack(employeeId, emp.getName(), curriculumFor(clinicId, employeeId, audience));
        });
    }

    @Override
    public AcademyAudience audienceOf(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> {
            var roleCode = dsl.select(ROLE.CODE).from(ROLE)
                    .join(MEMBERSHIP).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                    .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                    .limit(1)
                    .fetchOneInto(String.class);
            return switch (roleCode == null ? "core" : roleCode) {
                case "assistant" -> AcademyAudience.assistant;
                case "receptionist" -> AcademyAudience.receptionist;
                default -> AcademyAudience.core;
            };
        });
    }

    private List<TraineeUnit> curriculumFor(UUID clinicId, UUID employeeId, AcademyAudience audience) {
        return transactionTemplate.execute(tx -> {
        var units = dsl.selectFrom(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.CLINIC_ID.eq(clinicId))
                .and(ACADEMY_UNIT.ARCHIVED_AT.isNull())
                .and(ACADEMY_UNIT.APPLIES_TO.eq(AcademyAudience.core).or(ACADEMY_UNIT.APPLIES_TO.eq(audience)))
                .orderBy(ACADEMY_UNIT.DISPLAY_ORDER.asc())
                .fetch();
        var completedUnitIds = fetchCompletedUnitIds(clinicId, employeeId);
        var doneIds = new java.util.HashSet<>(completedUnitIds);
        boolean allPrevDone = true;
        var result = new ArrayList<TraineeUnit>();
        for (var u : units) {
            boolean photoRequired = u.getRequiresPhoto();
            boolean unitDone;
            if (photoRequired) {
                var latest = dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                        .where(ACADEMY_STEP_SUBMISSION.CLINIC_ID.eq(clinicId))
                        .and(ACADEMY_STEP_SUBMISSION.EMPLOYEE_ID.eq(employeeId))
                        .and(ACADEMY_STEP_SUBMISSION.UNIT_ID.eq(u.getId()))
                        .orderBy(ACADEMY_STEP_SUBMISSION.SUBMITTED_AT.desc())
                        .limit(1)
                        .fetchOne();
                unitDone = latest != null && latest.getStatus() == SubmissionStatus.verified;
            } else {
                unitDone = doneIds.contains(u.getId());
            }
            String status;
            if (unitDone) {
                status = "done";
                allPrevDone = true;
            } else if (allPrevDone) {
                status = "open";
                allPrevDone = false;
            } else {
                status = "locked";
            }
            var sections = parseJsonbSections(u.getContent());
            result.add(new TraineeUnit(
                    u.getId(), u.getAppliesTo(), u.getIcon(), u.getTitle(), u.getGoal(),
                    sections, u.getPhotoTask(), photoRequired,
                    dsl.fetchCount(ACADEMY_QUESTION, ACADEMY_QUESTION.UNIT_ID.eq(u.getId())),
                    status));
        }
        return result;
        });
    }

    @Override
    public Unit unit(UUID clinicId, UUID unitId) {
        return transactionTemplate.execute(status -> {
            var u = dsl.selectFrom(ACADEMY_UNIT)
                    .where(ACADEMY_UNIT.ID.eq(unitId))
                    .and(ACADEMY_UNIT.CLINIC_ID.eq(clinicId))
                    .and(ACADEMY_UNIT.ARCHIVED_AT.isNull())
                    .fetchOne();
            if (u == null) throw missing("الوحدة غير موجودة");
            var questions = dsl.selectFrom(ACADEMY_QUESTION)
                    .where(ACADEMY_QUESTION.UNIT_ID.eq(unitId))
                    .orderBy(ACADEMY_QUESTION.DISPLAY_ORDER.asc())
                    .fetch(q -> new Question(q.getId(), q.getPrompt(), parseOptions(q.getOptions()), q.getCorrectIndex()));
            return new Unit(
                    u.getId(), u.getAppliesTo(), u.getIcon(), u.getTitle(), u.getGoal(),
                    parseJsonbSections(u.getContent()), u.getPhotoTask(),
                    u.getRequiresPhoto(), "open", questions);
        });
    }

    @Override
    public Submission submitPhoto(UUID clinicId, Actor actor, UUID unitId, UUID photoId) {
        return transactionTemplate.execute(status -> {
            requireEmployee(clinicId, actor);
            var unit = requireUnit(clinicId, unitId);
            if (!unit.getRequiresPhoto()) throw missing("هذه الوحدة لا تتطلب صورة");
            var id = UUID.randomUUID();
            dsl.insertInto(ACADEMY_STEP_SUBMISSION)
                    .set(ACADEMY_STEP_SUBMISSION.ID, id)
                    .set(ACADEMY_STEP_SUBMISSION.CLINIC_ID, clinicId)
                    .set(ACADEMY_STEP_SUBMISSION.EMPLOYEE_ID, actor.employeeId())
                    .set(ACADEMY_STEP_SUBMISSION.UNIT_ID, unitId)
                    .set(ACADEMY_STEP_SUBMISSION.PHOTO_ID, photoId)
                    .set(ACADEMY_STEP_SUBMISSION.STATUS, SubmissionStatus.pending)
                    .execute();
            return submission(dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                    .where(ACADEMY_STEP_SUBMISSION.ID.eq(id)).fetchOne());
        });
    }

    @Override
    public TraineeUnit markDone(UUID clinicId, Actor actor, UUID unitId) {
        return transactionTemplate.execute(status -> {
            requireEmployee(clinicId, actor);
            var unit = requireUnit(clinicId, unitId);
            if (unit.getRequiresPhoto()) throw missing("هذه الوحدة تتطلب صورة — استخدم submitPhoto");
            dsl.insertInto(ACADEMY_STEP_SUBMISSION)
                    .set(ACADEMY_STEP_SUBMISSION.CLINIC_ID, clinicId)
                    .set(ACADEMY_STEP_SUBMISSION.EMPLOYEE_ID, actor.employeeId())
                    .set(ACADEMY_STEP_SUBMISSION.UNIT_ID, unitId)
                    .set(ACADEMY_STEP_SUBMISSION.STATUS, SubmissionStatus.verified)
                    .set(ACADEMY_STEP_SUBMISSION.VERIFIED_AT, OffsetDateTime.now())
                    .execute();
            return myCurriculum(clinicId, actor).stream()
                    .filter(u -> u.id().equals(unitId)).findFirst().orElseThrow(() -> missing("الوحدة غير موجودة"));
        });
    }

    @Override
    public List<Submission> pendingSubmissions(UUID clinicId) {
        return transactionTemplate.execute(status -> dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                .where(ACADEMY_STEP_SUBMISSION.CLINIC_ID.eq(clinicId))
                .and(ACADEMY_STEP_SUBMISSION.STATUS.eq(SubmissionStatus.pending))
                .fetch(this::submission));
    }

    @Override
    public Submission verify(UUID clinicId, Actor actor, UUID submissionId) {
        return transactionTemplate.execute(status -> {
            requireVerifier(clinicId, actor);
            var sub = dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                    .where(ACADEMY_STEP_SUBMISSION.ID.eq(submissionId))
                    .and(ACADEMY_STEP_SUBMISSION.CLINIC_ID.eq(clinicId))
                    .and(ACADEMY_STEP_SUBMISSION.STATUS.eq(SubmissionStatus.pending))
                    .fetchOne();
            if (sub == null) throw missing("التقديم غير موجود أو تم معالجته");
            dsl.update(ACADEMY_STEP_SUBMISSION)
                    .set(ACADEMY_STEP_SUBMISSION.STATUS, SubmissionStatus.verified)
                    .set(ACADEMY_STEP_SUBMISSION.VERIFIED_BY, actor.membershipId())
                    .set(ACADEMY_STEP_SUBMISSION.VERIFIED_AT, OffsetDateTime.now())
                    .where(ACADEMY_STEP_SUBMISSION.ID.eq(submissionId)).execute();
            return submission(dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                    .where(ACADEMY_STEP_SUBMISSION.ID.eq(submissionId)).fetchOne());
        });
    }

    @Override
    public Submission reject(UUID clinicId, Actor actor, UUID submissionId, String reason) {
        return transactionTemplate.execute(status -> {
            requireVerifier(clinicId, actor);
            var sub = dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                    .where(ACADEMY_STEP_SUBMISSION.ID.eq(submissionId))
                    .and(ACADEMY_STEP_SUBMISSION.CLINIC_ID.eq(clinicId))
                    .and(ACADEMY_STEP_SUBMISSION.STATUS.eq(SubmissionStatus.pending))
                    .fetchOne();
            if (sub == null) throw missing("التقديم غير موجود أو تم معالجته");
            dsl.update(ACADEMY_STEP_SUBMISSION)
                    .set(ACADEMY_STEP_SUBMISSION.STATUS, SubmissionStatus.rejected)
                    .set(ACADEMY_STEP_SUBMISSION.REJECT_REASON, reason)
                    .set(ACADEMY_STEP_SUBMISSION.VERIFIED_BY, actor.membershipId())
                    .set(ACADEMY_STEP_SUBMISSION.VERIFIED_AT, OffsetDateTime.now())
                    .where(ACADEMY_STEP_SUBMISSION.ID.eq(submissionId)).execute();
            return submission(dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                    .where(ACADEMY_STEP_SUBMISSION.ID.eq(submissionId)).fetchOne());
        });
    }

    @Override
    public Exam exam(UUID clinicId, Actor actor) {
        return transactionTemplate.execute(status -> {
            requireEmployee(clinicId, actor);
            var coveredUnits = completedCurriculumUnitIds(clinicId, actor);
            var passScore = dsl.select(CLINIC_SETTINGS.ACADEMY_PASS_SCORE)
                    .from(CLINIC_SETTINGS).where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId)).fetchOneInto(int.class);
            var questions = dsl.selectFrom(ACADEMY_QUESTION)
                    .where(ACADEMY_QUESTION.UNIT_ID.in(coveredUnits))
                    .fetch();
            Collections.shuffle(questions);
            var eqs = questions.stream()
                    .map(q -> new ExamQuestion(q.getId(), q.getPrompt(), parseOptions(q.getOptions())))
                    .toList();
            return new Exam(eqs, eqs.size(), passScore == 0 ? 70 : passScore);
        });
    }

    @Override
    public ExamResult submitExam(UUID clinicId, Actor actor, Map<UUID, Integer> answers) {
        return transactionTemplate.execute(status -> {
            requireEmployee(clinicId, actor);
            if (answers.isEmpty()) throw missing("أجب على كل الأسئلة");
            var coveredUnits = completedCurriculumUnitIds(clinicId, actor);
            var passScore = dsl.select(CLINIC_SETTINGS.ACADEMY_PASS_SCORE)
                    .from(CLINIC_SETTINGS).where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId)).fetchOneInto(int.class);
            var questionIds = dsl.select(ACADEMY_QUESTION.ID).from(ACADEMY_QUESTION)
                    .where(ACADEMY_QUESTION.UNIT_ID.in(coveredUnits))
                    .fetchSet(ACADEMY_QUESTION.ID);
            if (!questionIds.containsAll(answers.keySet())) throw missing("أسئلة الامتحان غير صالحة");
            if (!questionIds.equals(answers.keySet())) throw missing("أجب على كل الأسئلة");
            var allQuestions = dsl.selectFrom(ACADEMY_QUESTION).where(ACADEMY_QUESTION.ID.in(questionIds)).fetch();
            int total = allQuestions.size();
            int correct = 0;
            for (var q : allQuestions) {
                var selected = answers.get(q.getId());
                if (selected != null && selected == q.getCorrectIndex()) correct++;
            }
            int score = (int) Math.round(((double) correct / total) * 100);
            boolean passed = score >= (passScore == 0 ? 70 : passScore);
            UUID attemptId = UUID.randomUUID();
            dsl.insertInto(ACADEMY_EXAM_ATTEMPT)
                    .set(ACADEMY_EXAM_ATTEMPT.ID, attemptId)
                    .set(ACADEMY_EXAM_ATTEMPT.CLINIC_ID, clinicId)
                    .set(ACADEMY_EXAM_ATTEMPT.EMPLOYEE_ID, actor.employeeId())
                    .set(ACADEMY_EXAM_ATTEMPT.SCORE, score)
                    .set(ACADEMY_EXAM_ATTEMPT.PASSED, passed)
                    .set(ACADEMY_EXAM_ATTEMPT.TOTAL_QUESTIONS, total)
                    .set(ACADEMY_EXAM_ATTEMPT.CORRECT_COUNT, correct)
                    .execute();
            return new ExamResult(attemptId, score, correct, total, passed, passScore == 0 ? 70 : passScore);
        });
    }

    @Override
    public Certificate certificate(UUID clinicId, Actor actor) {
        return transactionTemplate.execute(status -> {
            requireEmployee(clinicId, actor);
            var units = myCurriculum(clinicId, actor);
            if (units.stream().anyMatch(u -> !"done".equals(u.status()))) throw missing("أكمل كل الوحدات أولاً");
            var questionCount = dsl.fetchCount(ACADEMY_QUESTION,
                    ACADEMY_QUESTION.UNIT_ID.in(units.stream().map(TraineeUnit::id).toList()));
            OffsetDateTime passedAt = OffsetDateTime.now();
            if (questionCount > 0) {
                var attempt = dsl.selectFrom(ACADEMY_EXAM_ATTEMPT)
                        .where(ACADEMY_EXAM_ATTEMPT.CLINIC_ID.eq(clinicId))
                        .and(ACADEMY_EXAM_ATTEMPT.EMPLOYEE_ID.eq(actor.employeeId()))
                        .and(ACADEMY_EXAM_ATTEMPT.PASSED.isTrue())
                        .orderBy(ACADEMY_EXAM_ATTEMPT.ATTEMPTED_AT.desc())
                        .limit(1)
                        .fetchOne();
                if (attempt == null) throw missing("اجتز الامتحان أولًا — لا توجد شهادة بعد");
                passedAt = attempt.getAttemptedAt();
            }
            var employee = dsl.selectFrom(EMPLOYEE)
                    .where(EMPLOYEE.ID.eq(actor.employeeId())).fetchOne();
            String name = employee != null ? employee.getName() : "موظف";
            var titles = units.stream().map(TraineeUnit::title).toList();
            var passScore = dsl.select(CLINIC_SETTINGS.ACADEMY_PASS_SCORE)
                    .from(CLINIC_SETTINGS).where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId)).fetchOneInto(int.class);
            return new Certificate(name, titles, passScore == 0 ? 70 : passScore, passedAt);
        });
    }

    private List<UUID> completedCurriculumUnitIds(UUID clinicId, Actor actor) {
        var units = requiredCurriculum(clinicId, actor);
        if (units.isEmpty()) {
            throw missing("لا يوجد منهج تدريبي");
        }
        if (units.stream().anyMatch(unit -> !"done".equals(unit.status()))) {
            throw missing("أكمل كل الوحدات أولاً");
        }
        return units.stream().map(TraineeUnit::id).toList();
    }

    private List<TraineeUnit> requiredCurriculum(UUID clinicId, Actor actor) {
        var audience = actorToAudience(actor);
        var units = curriculumFor(clinicId, actor.employeeId(), audience);
        if (units.stream().noneMatch(unit -> unit.appliesTo() == AcademyAudience.core)
                || (audience != AcademyAudience.core
                        && units.stream().noneMatch(unit -> unit.appliesTo() == audience))) {
            throw missing("لم يتم إعداد المنهج لهذه الوظيفة");
        }
        return units;
    }

    @Override
    public void importDefaultCurriculum(UUID clinicId, Actor actor) {
        transactionTemplate.executeWithoutResult(status -> {
            requireVerifier(clinicId, actor);
            if (dsl.fetchCount(ACADEMY_UNIT, ACADEMY_UNIT.CLINIC_ID.eq(clinicId)) > 0) {
                throw missing("المنهج مستورد بالفعل");
            }
            var templates = dsl.selectFrom(ACADEMY_TEMPLATE_UNIT)
                    .orderBy(ACADEMY_TEMPLATE_UNIT.DISPLAY_ORDER.asc())
                    .fetch();
            for (var tmpl : templates) {
                var unitId = UUID.randomUUID();
                dsl.insertInto(ACADEMY_UNIT)
                        .set(ACADEMY_UNIT.ID, unitId)
                        .set(ACADEMY_UNIT.CLINIC_ID, clinicId)
                        .set(ACADEMY_UNIT.APPLIES_TO, tmpl.getAppliesTo())
                        .set(ACADEMY_UNIT.TITLE, tmpl.getTitle())
                        .set(ACADEMY_UNIT.DISPLAY_ORDER, tmpl.getDisplayOrder())
                        .set(ACADEMY_UNIT.REQUIRES_PHOTO, tmpl.getPhotoTask() != null)
                        .set(ACADEMY_UNIT.ICON, tmpl.getIcon())
                        .set(ACADEMY_UNIT.GOAL, tmpl.getGoal())
                        .set(ACADEMY_UNIT.CONTENT, tmpl.getContent())
                        .set(ACADEMY_UNIT.PHOTO_TASK, tmpl.getPhotoTask())
                        .execute();
                var qTmpls = dsl.selectFrom(ACADEMY_TEMPLATE_QUESTION)
                        .where(ACADEMY_TEMPLATE_QUESTION.UNIT_ID.eq(tmpl.getId()))
                        .orderBy(ACADEMY_TEMPLATE_QUESTION.DISPLAY_ORDER.asc())
                        .fetch();
                for (var qt : qTmpls) {
                    dsl.insertInto(ACADEMY_QUESTION)
                            .set(ACADEMY_QUESTION.ID, UUID.randomUUID())
                            .set(ACADEMY_QUESTION.UNIT_ID, unitId)
                            .set(ACADEMY_QUESTION.PROMPT, qt.getPrompt())
                            .set(ACADEMY_QUESTION.OPTIONS, qt.getOptions())
                            .set(ACADEMY_QUESTION.CORRECT_INDEX, qt.getCorrectIndex())
                            .set(ACADEMY_QUESTION.DISPLAY_ORDER, qt.getDisplayOrder())
                            .execute();
                }
            }
        });
    }

    @Override
    public List<Unit> curriculum(UUID clinicId) {
        return transactionTemplate.execute(status -> {
            var records = dsl.selectFrom(ACADEMY_UNIT)
                    .where(ACADEMY_UNIT.CLINIC_ID.eq(clinicId))
                    .and(ACADEMY_UNIT.ARCHIVED_AT.isNull())
                    .orderBy(ACADEMY_UNIT.DISPLAY_ORDER.asc())
                    .fetch();
            return records.stream().map(u -> new Unit(
                    u.getId(), u.getAppliesTo(), u.getIcon(), u.getTitle(), u.getGoal(),
                    parseJsonbSections(u.getContent()), u.getPhotoTask(), u.getRequiresPhoto(),
                    "open",
                    dsl.selectFrom(ACADEMY_QUESTION)
                            .where(ACADEMY_QUESTION.UNIT_ID.eq(u.getId()))
                            .orderBy(ACADEMY_QUESTION.DISPLAY_ORDER.asc())
                            .fetch(q -> new Question(q.getId(), q.getPrompt(),
                                    parseOptions(q.getOptions()), q.getCorrectIndex()))))
                    .toList();
        });
    }

    @Override
    public Unit saveUnit(UUID clinicId, Actor actor, UnitRequest request) {
        return transactionTemplate.execute(status -> {
            requireVerifier(clinicId, actor);
            UUID unitId = request.id() == null ? UUID.randomUUID() : request.id();
            var contentJson = serializeJson(request.content());
            if (request.id() == null) {
                dsl.insertInto(ACADEMY_UNIT)
                        .set(ACADEMY_UNIT.ID, unitId)
                        .set(ACADEMY_UNIT.CLINIC_ID, clinicId)
                        .set(ACADEMY_UNIT.APPLIES_TO, request.appliesTo())
                        .set(ACADEMY_UNIT.TITLE, request.title())
                        .set(ACADEMY_UNIT.DISPLAY_ORDER, 0)
                        .set(ACADEMY_UNIT.REQUIRES_PHOTO, request.requiresPhoto())
                        .set(ACADEMY_UNIT.ICON, request.icon())
                        .set(ACADEMY_UNIT.GOAL, request.goal())
                        .set(ACADEMY_UNIT.CONTENT, JSONB.valueOf(contentJson))
                        .set(ACADEMY_UNIT.PHOTO_TASK, request.photoTask())
                        .execute();
            } else {
                var existing = dsl.selectFrom(ACADEMY_UNIT)
                        .where(ACADEMY_UNIT.ID.eq(unitId))
                        .and(ACADEMY_UNIT.CLINIC_ID.eq(clinicId))
                        .fetchOne();
                if (existing == null) throw missing("الوحدة غير موجودة");
                dsl.update(ACADEMY_UNIT)
                        .set(ACADEMY_UNIT.TITLE, request.title())
                        .set(ACADEMY_UNIT.APPLIES_TO, request.appliesTo())
                        .set(ACADEMY_UNIT.ICON, request.icon())
                        .set(ACADEMY_UNIT.GOAL, request.goal())
                        .set(ACADEMY_UNIT.CONTENT, JSONB.valueOf(contentJson))
                        .set(ACADEMY_UNIT.PHOTO_TASK, request.photoTask())
                        .set(ACADEMY_UNIT.REQUIRES_PHOTO, request.requiresPhoto())
                        .where(ACADEMY_UNIT.ID.eq(unitId)).execute();
            }
            dsl.deleteFrom(ACADEMY_QUESTION).where(ACADEMY_QUESTION.UNIT_ID.eq(unitId)).execute();
            if (request.questions() != null) {
                for (var q : request.questions()) {
                    var optsJson = serializeOptions(q.options());
                    dsl.insertInto(ACADEMY_QUESTION)
                            .set(ACADEMY_QUESTION.ID, q.id() != null ? q.id() : UUID.randomUUID())
                            .set(ACADEMY_QUESTION.UNIT_ID, unitId)
                            .set(ACADEMY_QUESTION.PROMPT, q.prompt())
                            .set(ACADEMY_QUESTION.OPTIONS, JSONB.valueOf(optsJson))
                            .set(ACADEMY_QUESTION.CORRECT_INDEX, q.correctIndex())
                            .set(ACADEMY_QUESTION.DISPLAY_ORDER, q.displayOrder())
                            .execute();
                }
            }
            return unit(clinicId, unitId);
        });
    }

    private java.util.Set<UUID> fetchCompletedUnitIds(UUID clinicId, UUID employeeId) {
        var ids = dsl.select(ACADEMY_STEP_SUBMISSION.UNIT_ID).from(ACADEMY_STEP_SUBMISSION)
                .where(ACADEMY_STEP_SUBMISSION.CLINIC_ID.eq(clinicId))
                .and(ACADEMY_STEP_SUBMISSION.EMPLOYEE_ID.eq(employeeId))
                .and(ACADEMY_STEP_SUBMISSION.STATUS.eq(SubmissionStatus.verified))
                .fetchSet(ACADEMY_STEP_SUBMISSION.UNIT_ID);
        return ids;
    }

    private com.clinicos.shared.jooq.tables.records.AcademyUnitRecord requireUnit(UUID clinicId, UUID unitId) {
        var u = dsl.selectFrom(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.ID.eq(unitId))
                .and(ACADEMY_UNIT.CLINIC_ID.eq(clinicId))
                .and(ACADEMY_UNIT.ARCHIVED_AT.isNull())
                .fetchOne();
        if (u == null) throw missing("الوحدة غير موجودة");
        return u;
    }

    private void requireEmployee(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null) throw missing("غير مصرح");
        if (actor.employeeId() == null) throw missing("حسابك غير مرتبط بملف موظف. تواصل مع مدير العيادة.");
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP)
                .where(MEMBERSHIP.ID.eq(actor.membershipId())).and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.EMPLOYEE_ID.eq(actor.employeeId()))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))))
            throw missing("غير مصرح");
    }

    private void requireVerifier(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null) throw missing("غير مصرح");
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP).join(ROLE).on(MEMBERSHIP.ROLE_ID.eq(ROLE.ID))
                .where(MEMBERSHIP.ID.eq(actor.membershipId()))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .and(ROLE.CODE.in("owner", "manager"))))
            throw missing("غير مصرح — تتطلب صلاحيات acadVerify");
    }

    private AcademyAudience actorToAudience(Actor actor) {
        return switch (actor.roleCode()) {
            case "assistant" -> AcademyAudience.assistant;
            case "receptionist" -> AcademyAudience.receptionist;
            default -> AcademyAudience.core;
        };
    }

    private List<Section> parseJsonbSections(JSONB content) {
        if (content == null) return List.of();
        try {
            return JSON.readValue(content.data(), SECTIONS_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseOptions(JSONB options) {
        if (options == null) return List.of();
        try {
            return JSON.readValue(options.data(), OPTIONS_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String serializeJson(List<Section> sections) {
        try {
            return JSON.writeValueAsString(sections == null ? List.of() : sections);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String serializeOptions(List<String> options) {
        try {
            return JSON.writeValueAsString(options == null ? List.of() : options);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Submission submission(com.clinicos.shared.jooq.tables.records.AcademyStepSubmissionRecord r) {
        if (r == null) throw missing("التقديم غير موجود");
        var unit = dsl.select(ACADEMY_UNIT.TITLE, ACADEMY_UNIT.ICON)
                .from(ACADEMY_UNIT).where(ACADEMY_UNIT.ID.eq(r.getUnitId())).fetchOne();
        var emp = dsl.select(EMPLOYEE.NAME).from(EMPLOYEE).where(EMPLOYEE.ID.eq(r.getEmployeeId())).fetchOne();
        return new Submission(
                r.getId(), r.getUnitId(),
                unit != null ? unit.get(ACADEMY_UNIT.TITLE) : "",
                unit != null ? unit.get(ACADEMY_UNIT.ICON) : null,
                r.getEmployeeId(),
                emp != null ? emp.get(EMPLOYEE.NAME) : "",
                r.getPhotoId(),
                r.getStatus().getLiteral(),
                r.getSubmittedAt(),
                r.getRejectReason());
    }

    private IllegalArgumentException missing(String message) {
        return new IllegalArgumentException(message);
    }
}
