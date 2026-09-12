package com.clinicos.staff.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.clinicos.shared.TenantContext;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinition;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinitionRequest;

@SpringBootTest(classes = Application.class)
class DefaultTaskDefinitionServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultTaskDefinitionService taskDefinitionService;

    private UUID clinicA;
    private UUID clinicB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createThenListReturnsTask() {
        TenantContext.set(clinicA);
        TaskDefinition created = taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("تنظيف", "fanni", "daily", "assistant", false, null, null));

        List<TaskDefinition> tasks = taskDefinitionService.list(clinicA);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name()).isEqualTo("تنظيف");
        assertThat(tasks.get(0).dimension()).isEqualTo("fanni");
        assertThat(tasks.get(0).frequency()).isEqualTo("daily");
        assertThat(tasks.get(0).roleCode()).isEqualTo("assistant");
        assertThat(tasks.get(0).requiresPhoto()).isFalse();
    }

    @Test
    void listFiltersByClinic() {
        TenantContext.set(clinicA);
        taskDefinitionService.create(clinicA, new TaskDefinitionRequest("مهنة أ", "fanni", "daily", "assistant", false, null, null));
        TenantContext.set(clinicB);
        taskDefinitionService.create(clinicB, new TaskDefinitionRequest("مهنة ب", "solooki", "weekly", "receptionist", true, null, null));

        TenantContext.set(clinicA);
        List<TaskDefinition> aTasks = taskDefinitionService.list(clinicA);
        TenantContext.set(clinicB);
        List<TaskDefinition> bTasks = taskDefinitionService.list(clinicB);

        assertThat(aTasks).hasSize(1);
        assertThat(aTasks.get(0).name()).isEqualTo("مهنة أ");
        assertThat(bTasks).hasSize(1);
        assertThat(bTasks.get(0).name()).isEqualTo("مهنة ب");
        assertThat(bTasks.get(0).requiresPhoto()).isTrue();
    }

    @Test
    void updateTaskChangesName() {
        TenantContext.set(clinicA);
        TaskDefinition created = taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("اسم قديم", "fanni", "daily", "assistant", false, null, null));

        taskDefinitionService.update(clinicA, created.id(),
                new TaskDefinitionRequest("اسم جديد", "solooki", "weekly", "receptionist", true, null, null));

        List<TaskDefinition> tasks = taskDefinitionService.list(clinicA);
        assertThat(tasks.get(0).name()).isEqualTo("اسم جديد");
        assertThat(tasks.get(0).dimension()).isEqualTo("solooki");
        assertThat(tasks.get(0).frequency()).isEqualTo("weekly");
        assertThat(tasks.get(0).roleCode()).isEqualTo("receptionist");
        assertThat(tasks.get(0).requiresPhoto()).isTrue();
    }

    @Test
    void deleteTaskSoftDeletes() {
        TenantContext.set(clinicA);
        TaskDefinition created = taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("للحذف", "fanni", "daily", "assistant", false, null, null));

        taskDefinitionService.delete(clinicA, created.id());

        List<TaskDefinition> tasks = taskDefinitionService.list(clinicA);
        assertThat(tasks).isEmpty();
    }

    @Test
    void deleteNonexistentThrows() {
        TenantContext.set(clinicA);

        assertThatThrownBy(() -> taskDefinitionService.delete(clinicA, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createCustomFrequencyPersistsEveryNAndInterval() {
        TenantContext.set(clinicA);
        TaskDefinition created = taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("مراجعة أدوية", "fanni", "custom", "assistant", false, 2, "week"));

        List<TaskDefinition> tasks = taskDefinitionService.list(clinicA);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).frequency()).isEqualTo("custom");
        assertThat(tasks.get(0).everyN()).isEqualTo(2);
        assertThat(tasks.get(0).intervalUnit()).isEqualTo("week");
        assertThat(created.everyN()).isEqualTo(2);
        assertThat(created.intervalUnit()).isEqualTo("week");
    }

    @Test
    void updateCustomFrequencyChangesEveryN() {
        TenantContext.set(clinicA);
        TaskDefinition created = taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("مهمة مخصصة", "ibda3", "custom", "assistant", false, 3, "month"));

        taskDefinitionService.update(clinicA, created.id(),
                new TaskDefinitionRequest("مهمة مخصصة", "ibda3", "custom", "assistant", true, 1, "day"));

        List<TaskDefinition> tasks = taskDefinitionService.list(clinicA);
        assertThat(tasks.get(0).everyN()).isEqualTo(1);
        assertThat(tasks.get(0).intervalUnit()).isEqualTo("day");
        assertThat(tasks.get(0).requiresPhoto()).isTrue();
    }

    @Test
    void customFrequencyWithoutEveryNThrows() {
        TenantContext.set(clinicA);

        assertThatThrownBy(() -> taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("مهمة ناقصة", "fanni", "custom", "assistant", false, null, "week")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonCustomWithEveryNThrows() {
        TenantContext.set(clinicA);

        assertThatThrownBy(() -> taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("مهمة خاطئة", "fanni", "daily", "assistant", false, 2, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankNameThrows() {
        TenantContext.set(clinicA);

        assertThatThrownBy(() -> taskDefinitionService.create(clinicA,
                new TaskDefinitionRequest("   ", "fanni", "daily", "assistant", false, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
