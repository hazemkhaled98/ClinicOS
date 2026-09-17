package com.clinicos.prep.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.prep.api.PrepChecklistService;
import com.clinicos.prep.api.PrepChecklistService.Actor;
import com.clinicos.prep.api.PrepChecklistService.ChecklistRequest;
import com.clinicos.prep.api.PrepChecklistService.ItemRequest;
import com.clinicos.prep.api.PrepChecklistService.SectionRequest;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class DefaultPrepChecklistServiceIT extends AbstractPostgresIntegrationTest {
    @Autowired
    private PrepChecklistService service;

    private UUID clinicA;
    private UUID clinicB;
    private Actor owner;
    private Actor manager;
    private Actor assistant;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
            owner = actor(connection, clinicA, "owner");
            manager = actor(connection, clinicA, "manager");
            assistant = actor(connection, clinicA, "assistant");
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void BRG18_editClearsApproval() {
        var checklist = approvedChecklist();

        var edited = save(clinicA, assistant, checklist.id(), "كشف معدل");

        assertThat(edited.status()).isEqualTo("draft");
        assertThat(edited.approvedBy()).isNull();
    }

    @Test
    void BRG18_assistantCannotApprove() {
        var checklist = draftChecklist();

        TenantContext.set(clinicA);
        assertThatThrownBy(() -> service.approve(clinicA, assistant, checklist.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BRG19_emptyStructureRejected() {
        TenantContext.set(clinicA);

        assertThatThrownBy(() -> service.save(clinicA, assistant, null, new ChecklistRequest("كشف", List.of())))
                .hasMessage("أضف قسمًا واحدًا على الأقل");
    }

    @Test
    void BRG20_sameDayResumes() {
        var checklist = approvedChecklist();
        var itemId = checklist.sections().getFirst().items().getFirst().id();

        TenantContext.set(clinicA);
        service.toggle(clinicA, assistant, checklist.id(), itemId, true);
        var resumed = service.today(clinicA, assistant, checklist.id());

        assertThat(resumed.checkedCount()).isEqualTo(1);
    }

    @Test
    void BRG20_nextDayIsIndependent() {
        var checklist = approvedChecklist();
        var itemId = checklist.sections().getFirst().items().getFirst().id();

        TenantContext.set(clinicA);
        service.toggle(clinicA, assistant, checklist.id(), itemId, true);
        var today = service.today(clinicA, assistant, checklist.id());
        assertThat(today.runDate()).isEqualTo(LocalDate.now());
        assertThat(today.checkedCount()).isEqualTo(1);
        try (var connection = superuser(); var statement = connection.prepareStatement("update prep_run set run_date = current_date - 1 where id = ?")) {
            statement.setObject(1, today.id());
            statement.executeUpdate();
        }
        var nextDay = service.today(clinicA, assistant, checklist.id());
        assertThat(nextDay.id()).isNotEqualTo(today.id());
        assertThat(nextDay.checkedCount()).isZero();
    }

    @Test
    void importTemplateDeepCopiesRows() {
        TenantContext.set(clinicA);
        var imported = service.importTemplate(clinicA, assistant, "examination");

        assertThat(imported.status()).isEqualTo("draft");
        assertThat(imported.sections()).isNotEmpty();
        assertThat(imported.sections().getFirst().items()).isNotEmpty();
    }

    @Test
    void archiveHidesChecklist() {
        var checklist = draftChecklist();

        TenantContext.set(clinicA);
        service.archive(clinicA, assistant, checklist.id());

        assertThat(service.list(clinicA)).isEmpty();
    }

    @Test
    void draftCannotRun() {
        var checklist = draftChecklist();

        TenantContext.set(clinicA);
        assertThatThrownBy(() -> service.today(clinicA, assistant, checklist.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clinicBCannotReadClinicA() {
        var checklist = draftChecklist();

        TenantContext.set(clinicB);
        assertThat(service.list(clinicB)).isEmpty();
        assertThatThrownBy(() -> service.get(clinicB, checklist.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetClearsChecks() {
        var checklist = approvedChecklist();
        var itemId = checklist.sections().getFirst().items().getFirst().id();

        TenantContext.set(clinicA);
        service.toggle(clinicA, assistant, checklist.id(), itemId, true);
        var reset = service.reset(clinicA, assistant, checklist.id());

        assertThat(reset.checkedCount()).isZero();
    }

    private PrepChecklistService.Checklist draftChecklist() {
        return save(clinicA, assistant, null, "كشف");
    }

    private PrepChecklistService.Checklist approvedChecklist() {
        var checklist = draftChecklist();
        TenantContext.set(clinicA);
        return service.approve(clinicA, manager, checklist.id());
    }

    private PrepChecklistService.Checklist save(UUID clinicId, Actor actor, UUID checklistId, String name) {
        TenantContext.set(clinicId);
        return service.save(clinicId, actor, checklistId,
                new ChecklistRequest(name, List.of(new SectionRequest("عام", List.of(new ItemRequest("أداة"))))));
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
