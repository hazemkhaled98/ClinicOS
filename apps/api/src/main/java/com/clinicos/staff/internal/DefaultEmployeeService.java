package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.jooq.tables.records.EmployeeRecord;
import com.clinicos.staff.api.EmployeeService;

@Service
public class DefaultEmployeeService implements EmployeeService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultEmployeeService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Employee> list(UUID clinicId) {
        return transactionTemplate.execute(status ->
                dsl.selectFrom(EMPLOYEE)
                        .where(EMPLOYEE.CLINIC_ID.eq(clinicId))
                        .and(EMPLOYEE.ARCHIVED_AT.isNull())
                        .orderBy(EMPLOYEE.HIRED_AT.desc(), EMPLOYEE.NAME.asc())
                        .fetch(this::toEmployee));
    }

    @Override
    public Employee create(UUID clinicId, EmployeeRequest request) {
        validate(request);
        return transactionTemplate.execute(status -> {
            UUID id = UUID.randomUUID();
            var hiredAt = request.hiredAt() != null ? request.hiredAt() : LocalDate.now();
            dsl.insertInto(EMPLOYEE)
                    .set(EMPLOYEE.ID, id)
                    .set(EMPLOYEE.CLINIC_ID, clinicId)
                    .set(EMPLOYEE.NAME, request.name())
                    .set(EMPLOYEE.STAFF_ROLE, toDbRole(request.staffRole()))
                    .set(EMPLOYEE.BASE_PAY, request.basePay() != null ? request.basePay() : BigDecimal.ZERO)
                    .set(EMPLOYEE.MAX_INCENTIVE, request.maxIncentive() != null ? request.maxIncentive() : BigDecimal.ZERO)
                    .set(EMPLOYEE.SHIFT_START, request.customShift() ? request.shiftStart() : null)
                    .set(EMPLOYEE.SHIFT_END, request.customShift() ? request.shiftEnd() : null)
                    .set(EMPLOYEE.CUSTOM_SHIFT, request.customShift())
                    .set(EMPLOYEE.HIRED_AT, hiredAt)
                    .execute();
            return new Employee(id, request.name(), request.staffRole(), request.basePay(),
                    request.maxIncentive(),
                    request.customShift() ? request.shiftStart() : null,
                    request.customShift() ? request.shiftEnd() : null,
                    request.customShift(), hiredAt, null);
        });
    }

    @Override
    public Employee update(UUID clinicId, UUID employeeId, EmployeeRequest request) {
        validate(request);
        return transactionTemplate.execute(status -> {
            int updated = dsl.update(EMPLOYEE)
                    .set(EMPLOYEE.NAME, request.name())
                    .set(EMPLOYEE.STAFF_ROLE, toDbRole(request.staffRole()))
                    .set(EMPLOYEE.BASE_PAY, request.basePay() != null ? request.basePay() : BigDecimal.ZERO)
                    .set(EMPLOYEE.MAX_INCENTIVE, request.maxIncentive() != null ? request.maxIncentive() : BigDecimal.ZERO)
                    .set(EMPLOYEE.SHIFT_START, request.customShift() ? request.shiftStart() : null)
                    .set(EMPLOYEE.SHIFT_END, request.customShift() ? request.shiftEnd() : null)
                    .set(EMPLOYEE.CUSTOM_SHIFT, request.customShift())
                    .where(EMPLOYEE.ID.eq(employeeId))
                    .and(EMPLOYEE.CLINIC_ID.eq(clinicId))
                    .and(EMPLOYEE.ARCHIVED_AT.isNull())
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("الموظف غير موجود");
            }
            return findOrThrow(clinicId, employeeId);
        });
    }

    @Override
    public Employee archive(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> {
            int archived = dsl.update(EMPLOYEE)
                    .set(EMPLOYEE.ARCHIVED_AT, OffsetDateTime.now())
                    .where(EMPLOYEE.ID.eq(employeeId))
                    .and(EMPLOYEE.CLINIC_ID.eq(clinicId))
                    .and(EMPLOYEE.ARCHIVED_AT.isNull())
                    .execute();
            if (archived == 0) {
                throw new IllegalArgumentException("الموظف غير موجود");
            }
            dsl.update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, (UUID) null)
                    .where(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                    .execute();
            return findOrThrow(clinicId, employeeId);
        });
    }

    private Employee findOrThrow(UUID clinicId, UUID employeeId) {
        EmployeeRecord record = dsl.selectFrom(EMPLOYEE)
                .where(EMPLOYEE.ID.eq(employeeId))
                .and(EMPLOYEE.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (record == null) {
            throw new IllegalArgumentException("الموظف غير موجود");
        }
        return toEmployee(record);
    }

    private void validate(EmployeeRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        if (request.name() == null || request.name().isBlank()) {
            fieldErrors.put("name", "اسم الموظف مطلوب");
        }
        if (request.staffRole() == null) {
            fieldErrors.put("staffRole", "المسمى الوظيفي مطلوب");
        }
        if (request.basePay() != null && request.basePay().signum() < 0) {
            fieldErrors.put("basePay", "المرتب الأساسي لا يمكن أن يكون سالباً");
        }
        if (request.maxIncentive() != null && request.maxIncentive().signum() < 0) {
            fieldErrors.put("maxIncentive", "الحافز الكامل لا يمكن أن يكون سالباً");
        }
        if (request.customShift() && (request.shiftStart() == null || request.shiftEnd() == null)) {
            fieldErrors.put("shift", "الشفت المخصص يتطلب وقت بداية ونهاية");
        }
        if (!fieldErrors.isEmpty()) {
            throw new EmployeeValidationException(fieldErrors);
        }
    }

    private Employee toEmployee(EmployeeRecord record) {
        return new Employee(
                record.getId(),
                record.getName(),
                fromDbRole(record.getStaffRole()),
                record.getBasePay(),
                record.getMaxIncentive(),
                record.getShiftStart(),
                record.getShiftEnd(),
                record.getCustomShift(),
                record.getHiredAt(),
                record.getArchivedAt());
    }

    private static com.clinicos.shared.jooq.enums.StaffRole toDbRole(StaffRole role) {
        return switch (role) {
            case ASSISTANT -> com.clinicos.shared.jooq.enums.StaffRole.assistant;
            case RECEPTIONIST -> com.clinicos.shared.jooq.enums.StaffRole.receptionist;
        };
    }

    private static StaffRole fromDbRole(com.clinicos.shared.jooq.enums.StaffRole role) {
        return switch (role) {
            case assistant -> StaffRole.ASSISTANT;
            case receptionist -> StaffRole.RECEPTIONIST;
        };
    }
}