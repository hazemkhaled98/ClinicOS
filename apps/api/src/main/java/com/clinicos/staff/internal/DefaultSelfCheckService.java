package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.SelfCheck.SELF_CHECK;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.jooq.tables.records.SelfCheckRecord;
import com.clinicos.staff.api.SelfCheckService;
import com.clinicos.staff.api.SelfCheckService.DayAttendance;

@Service
public class DefaultSelfCheckService implements SelfCheckService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultSelfCheckService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public DayAttendance today(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> readToday(clinicId, employeeId));
    }

    @Override
    public DayAttendance checkIn(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> {
            LocalDate today = LocalDate.now();
            SelfCheckRecord existing = findByEmployeeAndDate(clinicId, employeeId, today);
            if (existing != null) {
                return toDayAttendance(existing);
            }
            SelfCheckRecord inserted = dsl.insertInto(SELF_CHECK)
                    .set(SELF_CHECK.CLINIC_ID, clinicId)
                    .set(SELF_CHECK.EMPLOYEE_ID, employeeId)
                    .set(SELF_CHECK.WORK_DATE, today)
                    .set(SELF_CHECK.CHECKED_IN_AT, OffsetDateTime.now())
                    .onConflict(SELF_CHECK.EMPLOYEE_ID, SELF_CHECK.WORK_DATE)
                    .doNothing()
                    .returning(SELF_CHECK.fields())
                    .fetchOne();
            if (inserted != null) {
                return toDayAttendance(inserted);
            }
            return toDayAttendance(findByEmployeeAndDate(clinicId, employeeId, today));
        });
    }

    @Override
    public DayAttendance checkOut(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> {
            LocalDate today = LocalDate.now();
            SelfCheckRecord existing = findByEmployeeAndDate(clinicId, employeeId, today);
            if (existing == null) {
                throw new IllegalArgumentException("لازم تسجّل الحضور أولاً");
            }
            if (existing.getCheckedOutAt() != null) {
                return toDayAttendance(existing);
            }
            SelfCheckRecord updated = dsl.update(SELF_CHECK)
                    .set(SELF_CHECK.CHECKED_OUT_AT, OffsetDateTime.now())
                    .where(SELF_CHECK.ID.eq(existing.getId()))
                    .returning(SELF_CHECK.fields())
                    .fetchOne();
            return toDayAttendance(updated);
        });
    }

    private DayAttendance readToday(UUID clinicId, UUID employeeId) {
        LocalDate today = LocalDate.now();
        SelfCheckRecord record = findByEmployeeAndDate(clinicId, employeeId, today);
        if (record == null) {
            return new DayAttendance(today, null, null);
        }
        return toDayAttendance(record);
    }

    private SelfCheckRecord findByEmployeeAndDate(UUID clinicId, UUID employeeId, LocalDate date) {
        return dsl.selectFrom(SELF_CHECK)
                .where(SELF_CHECK.CLINIC_ID.eq(clinicId))
                .and(SELF_CHECK.EMPLOYEE_ID.eq(employeeId))
                .and(SELF_CHECK.WORK_DATE.eq(date))
                .fetchOne();
    }

    private DayAttendance toDayAttendance(SelfCheckRecord record) {
        return new DayAttendance(record.getWorkDate(), record.getCheckedInAt(), record.getCheckedOutAt());
    }
}