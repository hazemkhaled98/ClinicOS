package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.jooq.tables.records.EmployeeRecord;
import com.clinicos.staff.api.EmployeeService;

@Service
public class DefaultEmployeeService implements EmployeeService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final NotificationService notificationService;

    public DefaultEmployeeService(DSLContext dsl, TransactionTemplate transactionTemplate,
            NotificationService notificationService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.notificationService = notificationService;
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
    public Employee findByMembership(UUID clinicId, UUID membershipId) {
        return transactionTemplate.execute(status -> {
            EmployeeRecord record = dsl.select(EMPLOYEE.fields())
                    .from(MEMBERSHIP)
                    .join(EMPLOYEE).on(EMPLOYEE.ID.eq(MEMBERSHIP.EMPLOYEE_ID))
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(EMPLOYEE.ARCHIVED_AT.isNull())
                    .fetchOneInto(EmployeeRecord.class);
            return record == null ? null : toEmployee(record);
        });
    }

    @Override
    public Employee findById(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> {
            EmployeeRecord record = dsl.selectFrom(EMPLOYEE)
                    .where(EMPLOYEE.ID.eq(employeeId))
                    .and(EMPLOYEE.CLINIC_ID.eq(clinicId))
                    .and(EMPLOYEE.ARCHIVED_AT.isNull())
                    .fetchOneInto(EmployeeRecord.class);
            return record == null ? null : toEmployee(record);
        });
    }

    @Override
    public Employee create(UUID clinicId, EmployeeRequest request) {
        validate(request);
        try {
            return transactionTemplate.execute(status -> {
                UUID id = UUID.randomUUID();
                var hiredAt = request.hiredAt() != null ? request.hiredAt() : LocalDate.now();
                dsl.insertInto(EMPLOYEE)
                        .set(EMPLOYEE.ID, id)
                        .set(EMPLOYEE.CLINIC_ID, clinicId)
                        .set(EMPLOYEE.NAME, request.name())
                        .set(EMPLOYEE.BASE_PAY, request.basePay() != null ? request.basePay() : BigDecimal.ZERO)
                        .set(EMPLOYEE.MAX_INCENTIVE, request.maxIncentive() != null ? request.maxIncentive() : BigDecimal.ZERO)
                        .set(EMPLOYEE.SHIFT_START, request.customShift() ? request.shiftStart() : null)
                        .set(EMPLOYEE.SHIFT_END, request.customShift() ? request.shiftEnd() : null)
                        .set(EMPLOYEE.CUSTOM_SHIFT, request.customShift())
                        .set(EMPLOYEE.HIRED_AT, hiredAt)
                        .execute();
                return new Employee(id, request.name(), request.basePay(),
                        request.maxIncentive(),
                        request.customShift() ? request.shiftStart() : null,
                        request.customShift() ? request.shiftEnd() : null,
                        request.customShift(), hiredAt, null);
            });
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("اسم الموظف موجود مسبقاً");
        }
    }

    @Override
    public Employee update(UUID clinicId, UUID employeeId, EmployeeRequest request, UUID actorMembershipId) {
        validate(request);
        return transactionTemplate.execute(status -> {
            int updated = dsl.update(EMPLOYEE)
                    .set(EMPLOYEE.NAME, request.name())
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
            Employee employee = findOrThrow(clinicId, employeeId);
            notifyOwners(clinicId, actorMembershipId, employee.name());
            return employee;
        });
    }

    @Override
    public Employee archive(UUID clinicId, UUID employeeId, UUID actorMembershipId) {
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
            Employee employee = findOrThrow(clinicId, employeeId);
            notifyOwners(clinicId, actorMembershipId, employee.name());
            return employee;
        });
    }

    private void notifyOwners(UUID clinicId, UUID actorMembershipId, String name) {
        notificationService.notifyRoles(clinicId, actorMembershipId, Set.of("owner"),
                NotificationKind.EMPLOYEE_CHANGED, Map.of("employee", name));
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
        if (request.basePay() != null && request.basePay().signum() < 0) {
            fieldErrors.put("basePay", "المرتب الأساسي لا يمكن أن يكون سالباً");
        }
        if (request.maxIncentive() != null && request.maxIncentive().signum() < 0) {
            fieldErrors.put("maxIncentive", "الحافز الكامل لا يمكن أن يكون سالباً");
        }
        if (request.customShift() && (request.shiftStart() == null || request.shiftEnd() == null)) {
            fieldErrors.put("shift", "الشفت المخصص يتطلب وقت بداية ونهاية");
        } else if (request.customShift() && request.shiftStart().isAfter(request.shiftEnd())) {
            fieldErrors.put("shift", "وقت بداية الشفت يجب أن يسبق وقت النهاية");
        }
        if (!fieldErrors.isEmpty()) {
            throw new EmployeeValidationException(fieldErrors);
        }
    }

    private Employee toEmployee(EmployeeRecord record) {
        return new Employee(
                record.getId(),
                record.getName(),
                record.getBasePay(),
                record.getMaxIncentive(),
                record.getShiftStart(),
                record.getShiftEnd(),
                record.getCustomShift(),
                record.getHiredAt(),
                record.getArchivedAt());
    }
}