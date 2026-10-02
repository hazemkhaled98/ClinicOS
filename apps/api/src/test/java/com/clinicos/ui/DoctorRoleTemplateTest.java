package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.staff.api.EmployeeService.Employee;

class DoctorRoleTemplateTest {

    @Test
    void doctorCanBeSelectedWhenCreatingAndEditingAccounts() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        Context usersContext = new Context(Locale.ROOT);
        usersContext.setVariable("users", List.of());
        usersContext.setVariable("currentMembershipId", UUID.randomUUID());
        usersContext.setVariable("addForm", UserAdminController.UserForm.empty());
        usersContext.setVariable("roleNames", Map.of("doctor", "طبيب"));
        usersContext.setVariable("actorRole", "owner");
        String usersHtml = engine.process("admin/users", Set.of("usersCard"), usersContext);

        UUID employeeId = UUID.randomUUID();
        Employee employee = new Employee(employeeId, "الطبيب", null, null, null, null, false, null, null);
        UserSummary doctor = new UserSummary(UUID.randomUUID(), "doctor", "طبيب", null, "active", "doctor",
                UUID.randomUUID(), employeeId);
        Context employeesContext = new Context(Locale.ROOT);
        employeesContext.setVariable("employees", List.of(employee));
        employeesContext.setVariable("employeeRoles", Map.of(employeeId, doctor));
        employeesContext.setVariable("actorRole", "owner");
        String employeesHtml = engine.process("admin/employees", Set.of("employeesCard"), employeesContext);

        assertThat(usersHtml).contains("value=\"doctor\"", "طبيب");
        assertThat(employeesHtml).contains("value=\"doctor\"", "طبيب");
    }
}
