package com.clinicos.academy.internal;

import static com.clinicos.shared.jooq.tables.AcademyQuestion.ACADEMY_QUESTION;
import static com.clinicos.shared.jooq.tables.AcademyStepSubmission.ACADEMY_STEP_SUBMISSION;
import static com.clinicos.shared.jooq.tables.AcademyUnit.ACADEMY_UNIT;
import static com.clinicos.shared.jooq.tables.Attachment.ATTACHMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.academy.AcademyService;
import com.clinicos.academy.AcademyService.Actor;
import com.clinicos.academy.AcademyService.Submission;
import com.clinicos.academy.AcademyService.TraineeUnit;
import com.clinicos.academy.AcademyService.Unit;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.AcademyAudience;
import com.clinicos.shared.jooq.enums.SubmissionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import com.clinicos.shared.jooq.tables.records.AcademyStepSubmissionRecord;
import com.clinicos.shared.jooq.tables.records.AcademyUnitRecord;

@SpringBootTest(classes = Application.class)
class DefaultAcademyServiceIT extends AbstractPostgresIntegrationTest {
    @Autowired
    private AcademyService service;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID clinicA;
    private UUID clinicB;
    private Actor owner;
    private Actor manager;
    private Actor assistant;
    private Actor receptionist;
    private Actor clinicBAssistant;
    private UUID photo;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
            owner = actor(connection, clinicA, "owner");
            manager = actor(connection, clinicA, "manager");
            assistant = actor(connection, clinicA, "assistant");
            receptionist = actor(connection, clinicA, "receptionist");
            clinicBAssistant = actor(connection, clinicB, "assistant");
            seedCurriculum(connection, clinicA);
            seedCurriculum(connection, clinicB);
            photo = insertAttachment(connection, clinicA);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void BRG21_roleUnitsPlusCore() {
        TenantContext.set(clinicA);
        var audience = service.myCurriculum(clinicA, assistant).stream()
                .map(TraineeUnit::appliesTo).toList();
        assertThat(audience).contains(AcademyAudience.core).contains(AcademyAudience.assistant);
        assertThat(audience).doesNotContain(AcademyAudience.receptionist);
    }

    @Test
    void BRG22_photoUnitNeedsVerification() {
        TenantContext.set(clinicA);
        var unit = photoUnit();
        assertThat(status(unit)).isEqualTo("open");
        service.submitPhoto(clinicA, assistant, unit, photo);
        assertThat(status(unit)).isEqualTo("open");
        var sub = latest(assistant.employeeId(), unit);
        service.verify(clinicA, manager, sub.getId());
        assertThat(status(unit)).isEqualTo("done");
    }

    @Test
    void BRG22_rejectionReopensUnit() {
        TenantContext.set(clinicA);
        var unit = photoUnit();
        var sub = service.submitPhoto(clinicA, assistant, unit, photo);
        var rejected = service.reject(clinicA, manager, sub.id(), "الصورة غير واضحة");
        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.rejectReason()).isEqualTo("الصورة غير واضحة");
        assertThat(status(unit)).isEqualTo("open");
    }

    @Test
    void BRG22_resubmissionAfterRejection() {
        TenantContext.set(clinicA);
        var unit = photoUnit();
        var first = service.submitPhoto(clinicA, assistant, unit, photo);
        service.reject(clinicA, manager, first.id(), "راجع");
        var second = service.submitPhoto(clinicA, assistant, unit, photo);
        assertThat(second.id()).isNotEqualTo(first.id());
        service.verify(clinicA, manager, second.id());
        assertThat(status(unit)).isEqualTo("done");
    }

    @Test
    void BRG23_examPoolOnlyCoveredUnits() {
        TenantContext.set(clinicA);
        var covered = photoUnit();
        var sub = service.submitPhoto(clinicA, assistant, covered, photo);
        service.verify(clinicA, manager, sub.id());
        var notCovered = nonPhotoUnit();
        service.markDone(clinicA, assistant, notCovered);
        var exam = service.exam(clinicA, assistant);
        assertThat(exam.totalQuestions()).isGreaterThan(0);
        for (var q : exam.questions()) {
            UUID unitId = unitOfQuestion(q.id());
            assertThat(unitId).isIn(covered, notCovered);
        }
    }

    @Test
    void BRG24_certificateNeedsPass() {
        TenantContext.set(clinicA);
        assertThatThrownBy(() -> service.certificate(clinicA, assistant))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("أكمل كل الوحدات");
        completeAll();
        assertThatThrownBy(() -> service.certificate(clinicA, assistant))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("اجتز الامتحان");
        assertThat(service.submitExam(clinicA, assistant, correctAnswers()).passed()).isTrue();
        assertThat(service.certificate(clinicA, assistant)).isNotNull();
    }

    @Test
    void BRG24_certificateNeedsAllUnitsDone() {
        TenantContext.set(clinicA);
        var units = learnerUnits();
        markDoneIfNonPhoto(clinicA, assistant, units.get(0));
        assertThatThrownBy(() -> service.certificate(clinicA, assistant))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("أكمل كل الوحدات");
    }

    @Test
    void A2_retakeAfterFail() {
        TenantContext.set(clinicA);
        completeAll();
        var failed = service.submitExam(clinicA, assistant, wrongAnswers());
        assertThat(failed.passed()).isFalse();
        var passed = service.submitExam(clinicA, assistant, correctAnswers());
        assertThat(passed.passed()).isTrue();
        assertThat(passed.attemptId()).isNotEqualTo(failed.attemptId());
    }

    @Test
    void selfVerificationRejected() {
        TenantContext.set(clinicA);
        var sub = service.submitPhoto(clinicA, assistant, photoUnit(), photo);
        assertThatThrownBy(() -> service.verify(clinicA, assistant, sub.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("غير مصرح");
        assertThatThrownBy(() -> service.reject(clinicA, assistant, sub.id(), "سبب"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clinicBCannotReadClinicA() {
        TenantContext.set(clinicB);
        assertThat(service.myCurriculum(clinicB, clinicBAssistant)).isNotEmpty();
        UUID aUnitId = transactionTemplate.execute(tx -> dsl.select(ACADEMY_UNIT.ID).from(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.CLINIC_ID.eq(clinicA)).limit(1).fetchOne(ACADEMY_UNIT.ID));
        assertThatThrownBy(() -> service.unit(clinicB, aUnitId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importDeepCopiesCatalog() {
        TenantContext.set(clinicA);
        int beforeA = transactionTemplate.execute(tx -> dsl.fetchCount(ACADEMY_UNIT, ACADEMY_UNIT.CLINIC_ID.eq(clinicA)));
        int beforeB = transactionTemplate.execute(tx -> dsl.fetchCount(ACADEMY_UNIT, ACADEMY_UNIT.CLINIC_ID.eq(clinicB)));
        service.importDefaultCurriculum(clinicA, owner);
        int afterA = transactionTemplate.execute(tx -> dsl.fetchCount(ACADEMY_UNIT, ACADEMY_UNIT.CLINIC_ID.eq(clinicA)));
        int afterB = transactionTemplate.execute(tx -> dsl.fetchCount(ACADEMY_UNIT, ACADEMY_UNIT.CLINIC_ID.eq(clinicB)));
        assertThat(afterA).isGreaterThan(beforeA);
        assertThat(afterB).isEqualTo(beforeB);
        var imported = transactionTemplate.execute(tx -> dsl.selectFrom(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.CLINIC_ID.eq(clinicA))
                .and(ACADEMY_UNIT.TITLE.eq("عقلية النجاح والمبادرة")).fetchOne());
        assertThat(imported).isNotNull();
        Integer questionCount = transactionTemplate.execute(tx -> dsl.fetchCount(ACADEMY_QUESTION,
                ACADEMY_QUESTION.UNIT_ID.eq(imported.getId())));
        assertThat(questionCount).isGreaterThan(0);
    }

    @Test
    void sequentialUnlockBlocksLaterUnits() {
        TenantContext.set(clinicA);
        var units = transactionTemplate.execute(tx -> dsl.selectFrom(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.CLINIC_ID.eq(clinicA))
                .and(ACADEMY_UNIT.APPLIES_TO.eq(AcademyAudience.core))
                .orderBy(ACADEMY_UNIT.DISPLAY_ORDER.asc()).fetch());
        UUID first = units.get(0).getId();
        UUID second = units.get(1).getId();
        assertThat(status(first)).isEqualTo("open");
        assertThat(status(second)).isEqualTo("locked");
        var sub = service.submitPhoto(clinicA, assistant, first, photo);
        service.verify(clinicA, manager, sub.id());
        assertThat(status(first)).isEqualTo("done");
        assertThat(status(second)).isEqualTo("open");
    }

    @Test
    void saveUnitUpsertsAndQuestions() {
        TenantContext.set(clinicA);
        var request = new AcademyService.UnitRequest(
                null, AcademyAudience.core, "⭐", "وحدة اختبار", "هدف",
                List.of(new AcademyService.Section("قسم", List.of("نقطة"))), null, false,
                List.of(new AcademyService.QuestionRequest(null, "سؤال؟",
                        List.of("صح", "غلط"), 0, 1)));
        var saved = service.saveUnit(clinicA, owner, request);
        assertThat(saved.questions()).hasSize(1);
        Unit loaded = service.unit(clinicA, saved.id());
        assertThat(loaded.title()).isEqualTo("وحدة اختبار");
        assertThat(loaded.questions().getFirst().prompt()).isEqualTo("سؤال؟");
    }

    @Test
    void markDoneOnlyForNonPhoto() {
        TenantContext.set(clinicA);
        var photo = photoUnit();
        assertThatThrownBy(() -> service.markDone(clinicA, assistant, photo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("تتطلب صورة");
    }

    private void completeAll() {
        for (TraineeUnit u : learnerUnits()) {
            markDoneIfNonPhoto(clinicA, assistant, u);
        }
    }

    private void markDoneIfNonPhoto(UUID clinicId, Actor actor, TraineeUnit u) {
        var rec = transactionTemplate.execute(tx -> dsl.selectFrom(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.ID.eq(u.id())).fetchOne());
        if (rec.getRequiresPhoto()) {
            var sub = service.submitPhoto(clinicId, actor, u.id(), photo);
            service.verify(clinicId, manager, sub.id());
        } else {
            service.markDone(clinicId, actor, u.id());
        }
    }

    private List<TraineeUnit> learnerUnits() {
        return service.myCurriculum(clinicA, assistant);
    }

    private String status(UUID unitId) {
        return service.myCurriculum(clinicA, assistant).stream()
                .filter(u -> u.id().equals(unitId)).findFirst().orElseThrow().status();
    }

    private UUID photoUnit() {
        return transactionTemplate.execute(tx -> dsl.selectFrom(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.CLINIC_ID.eq(clinicA))
                .and(ACADEMY_UNIT.REQUIRES_PHOTO.isTrue()).orderBy(ACADEMY_UNIT.DISPLAY_ORDER.asc())
                .limit(1).fetchOne().getId());
    }

    private UUID nonPhotoUnit() {
        return transactionTemplate.execute(tx -> dsl.selectFrom(ACADEMY_UNIT)
                .where(ACADEMY_UNIT.CLINIC_ID.eq(clinicA))
                .and(ACADEMY_UNIT.REQUIRES_PHOTO.isFalse()).and(ACADEMY_UNIT.APPLIES_TO.eq(AcademyAudience.core))
                .orderBy(ACADEMY_UNIT.DISPLAY_ORDER.asc()).limit(1).fetchOne().getId());
    }

    private AcademyStepSubmissionRecord latest(UUID employeeId, UUID unitId) {
        return transactionTemplate.execute(tx -> dsl.selectFrom(ACADEMY_STEP_SUBMISSION)
                .where(ACADEMY_STEP_SUBMISSION.EMPLOYEE_ID.eq(employeeId))
                .and(ACADEMY_STEP_SUBMISSION.UNIT_ID.eq(unitId))
                .orderBy(ACADEMY_STEP_SUBMISSION.SUBMITTED_AT.desc()).limit(1).fetchOne());
    }

    private UUID unitOfQuestion(UUID questionId) {
        return transactionTemplate.execute(tx -> dsl.select(ACADEMY_QUESTION.UNIT_ID).from(ACADEMY_QUESTION)
                .where(ACADEMY_QUESTION.ID.eq(questionId)).fetchOne(ACADEMY_QUESTION.UNIT_ID));
    }

    private Map<UUID, Integer> answers(boolean correct) {
        Map<UUID, Integer> m = new LinkedHashMap<>();
        var questions = transactionTemplate.execute(tx -> dsl.selectFrom(ACADEMY_QUESTION)
                .where(ACADEMY_QUESTION.UNIT_ID.in(dsl.select(ACADEMY_STEP_SUBMISSION.UNIT_ID)
                        .from(ACADEMY_STEP_SUBMISSION)
                        .where(ACADEMY_STEP_SUBMISSION.EMPLOYEE_ID.eq(assistant.employeeId()).and(
                                ACADEMY_STEP_SUBMISSION.STATUS.eq(SubmissionStatus.verified))))).fetch());
        for (var q : questions) {
            m.put(q.getId(), correct ? q.getCorrectIndex() : (q.getCorrectIndex() == 0 ? 1 : 0));
        }
        return m;
    }

    private Map<UUID, Integer> correctAnswers() {
        return answers(true);
    }

    private Map<UUID, Integer> wrongAnswers() {
        return answers(false);
    }

    private static void seedCurriculum(Connection connection, UUID clinicId) {
        DSLContext c = DSL.using(connection, SQLDialect.POSTGRES);
        AcademyUnitRecord uPhoto = c.insertInto(ACADEMY_UNIT,
                ACADEMY_UNIT.CLINIC_ID, ACADEMY_UNIT.APPLIES_TO, ACADEMY_UNIT.TITLE,
                ACADEMY_UNIT.DISPLAY_ORDER, ACADEMY_UNIT.REQUIRES_PHOTO)
                .values(clinicId, AcademyAudience.core, "وحدة تصوير", 1, true)
                .returning().fetchOne();
        AcademyUnitRecord u1 = c.insertInto(ACADEMY_UNIT,
                ACADEMY_UNIT.CLINIC_ID, ACADEMY_UNIT.APPLIES_TO, ACADEMY_UNIT.TITLE,
                ACADEMY_UNIT.DISPLAY_ORDER, ACADEMY_UNIT.REQUIRES_PHOTO)
                .values(clinicId, AcademyAudience.core, "وحدة أساسية", 2, false)
                .returning().fetchOne();
        AcademyUnitRecord u2 = c.insertInto(ACADEMY_UNIT,
                ACADEMY_UNIT.CLINIC_ID, ACADEMY_UNIT.APPLIES_TO, ACADEMY_UNIT.TITLE,
                ACADEMY_UNIT.DISPLAY_ORDER, ACADEMY_UNIT.REQUIRES_PHOTO)
                .values(clinicId, AcademyAudience.core, "وحدة أساسية ثانية", 3, false)
                .returning().fetchOne();
        AcademyUnitRecord u3 = c.insertInto(ACADEMY_UNIT,
                ACADEMY_UNIT.CLINIC_ID, ACADEMY_UNIT.APPLIES_TO, ACADEMY_UNIT.TITLE,
                ACADEMY_UNIT.DISPLAY_ORDER, ACADEMY_UNIT.REQUIRES_PHOTO)
                .values(clinicId, AcademyAudience.assistant, "وحدة مساعد", 4, false)
                .returning().fetchOne();
        c.insertInto(ACADEMY_UNIT,
                ACADEMY_UNIT.CLINIC_ID, ACADEMY_UNIT.APPLIES_TO, ACADEMY_UNIT.TITLE,
                ACADEMY_UNIT.DISPLAY_ORDER, ACADEMY_UNIT.REQUIRES_PHOTO)
                .values(clinicId, AcademyAudience.receptionist, "وحدة استقبال", 5, false).execute();
        insertQuestion(c, uPhoto.getId(), "سؤال تصوير", 0);
        insertQuestion(c, u1.getId(), "سؤال أساسية", 1);
        insertQuestion(c, u2.getId(), "سؤال أساسية ثانية", 2);
        insertQuestion(c, u3.getId(), "سؤال مساعد", 2);
    }

    private static UUID insertAttachment(Connection connection, UUID clinicId) throws Exception {
        try (var st = connection.prepareStatement(
                "insert into attachment (id, clinic_id, storage_key, content_type, byte_size) values (?, ?, 'academy/' || ?, 'image/png', 1) returning id")) {
            UUID id = UUID.randomUUID();
            st.setObject(1, id);
            st.setObject(2, clinicId);
            st.setObject(3, id);
            var rs = st.executeQuery();
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private static void insertQuestion(DSLContext c, UUID unitId, String prompt, int correct) {
        c.insertInto(ACADEMY_QUESTION, ACADEMY_QUESTION.UNIT_ID, ACADEMY_QUESTION.PROMPT,
                ACADEMY_QUESTION.OPTIONS, ACADEMY_QUESTION.CORRECT_INDEX, ACADEMY_QUESTION.DISPLAY_ORDER)
                .values(unitId, prompt, JSONB.valueOf("[\"أ\",\"ب\",\"ج\"]"), correct, 1).execute();
    }

    private Actor actor(java.sql.Connection connection, UUID clinicId, String role) throws Exception {
        var membershipId = TestFixtures.insertMembership(connection, clinicId,
                TestFixtures.insertUser(connection, clinicId, role + UUID.randomUUID(), "password", "active"), role);
        var employeeId = TestFixtures.insertEmployee(connection, clinicId, role);
        TestFixtures.linkMembershipToEmployee(connection, membershipId, employeeId);
        return new Actor(membershipId, role, employeeId);
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
