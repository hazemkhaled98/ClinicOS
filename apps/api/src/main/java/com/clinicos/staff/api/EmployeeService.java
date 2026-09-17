package com.clinicos.staff.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Employee roster for one clinic. Every mutation and read runs inside the
 * clinic bound to the current thread's {@code TenantContext}; callers must
 * guarantee a tenant is bound before invoking (TenantSessionFilter does for
 * HTTP requests, tests seed it explicitly).
 *
 * <p>BR-G04: an employee's role is their clinic membership role
 * (membership → role); it drives permissions, the task list, academy
 * curriculum and inventory areas.
 */
public interface EmployeeService {

    List<Employee> list(UUID clinicId);

    Employee findByMembership(UUID clinicId, UUID membershipId);

    Employee findById(UUID clinicId, UUID employeeId);

    Employee create(UUID clinicId, EmployeeRequest request);

    Employee update(UUID clinicId, UUID employeeId, EmployeeRequest request);

    Employee archive(UUID clinicId, UUID employeeId);

    record Employee(
            UUID id,
            String name,
            BigDecimal basePay,
            BigDecimal maxIncentive,
            LocalTime shiftStart,
            LocalTime shiftEnd,
            boolean customShift,
            LocalDate hiredAt,
            OffsetDateTime archivedAt) {
    }

    record EmployeeRequest(
            String name,
            BigDecimal basePay,
            BigDecimal maxIncentive,
            LocalTime shiftStart,
            LocalTime shiftEnd,
            boolean customShift,
            LocalDate hiredAt) {
    }

    /**
     * Field-level validation failure (Arabic messages, keyed by field name).
     * Raised before any SQL runs so the check {@code custom_shift = (shift_start
     * is not null and shift_end is not null)} never surfaces as a 500.
     */
    class EmployeeValidationException extends RuntimeException {
        private final Map<String, String> fieldErrors;

        public EmployeeValidationException(Map<String, String> fieldErrors) {
            super(String.join("؛ ", fieldErrors.values()));
            this.fieldErrors = fieldErrors;
        }

        public Map<String, String> fieldErrors() {
            return fieldErrors;
        }
    }
}