package com.clinicos.prep.internal;

import static com.clinicos.shared.jooq.tables.PrepChecklist.PREP_CHECKLIST;
import static com.clinicos.shared.jooq.tables.PrepItem.PREP_ITEM;
import static com.clinicos.shared.jooq.tables.PrepRun.PREP_RUN;
import static com.clinicos.shared.jooq.tables.PrepRunItem.PREP_RUN_ITEM;
import static com.clinicos.shared.jooq.tables.PrepSection.PREP_SECTION;
import static com.clinicos.shared.jooq.tables.PrepTemplate.PREP_TEMPLATE;
import static com.clinicos.shared.jooq.tables.PrepTemplateItem.PREP_TEMPLATE_ITEM;
import static com.clinicos.shared.jooq.tables.PrepTemplateSection.PREP_TEMPLATE_SECTION;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.clinicos.prep.api.PrepChecklistService;
import com.clinicos.prep.api.PrepChecklistService.Actor;
import com.clinicos.prep.api.PrepChecklistService.Checklist;
import com.clinicos.prep.api.PrepChecklistService.ChecklistRequest;
import com.clinicos.prep.api.PrepChecklistService.Item;
import com.clinicos.prep.api.PrepChecklistService.ItemRequest;
import com.clinicos.prep.api.PrepChecklistService.Run;
import com.clinicos.prep.api.PrepChecklistService.Section;
import com.clinicos.prep.api.PrepChecklistService.SectionRequest;
import com.clinicos.prep.api.PrepChecklistService.Template;
import com.clinicos.shared.jooq.enums.ChecklistStatus;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.tables.records.PrepChecklistRecord;
import com.clinicos.shared.jooq.tables.records.PrepRunRecord;

@Service
public class DefaultPrepChecklistService implements PrepChecklistService {
    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultPrepChecklistService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Checklist> list(UUID clinicId) {
        return transactionTemplate.execute(status -> dsl.selectFrom(PREP_CHECKLIST)
                .where(PREP_CHECKLIST.CLINIC_ID.eq(clinicId))
                .and(PREP_CHECKLIST.ARCHIVED_AT.isNull())
                .orderBy(PREP_CHECKLIST.NAME.asc())
                .fetch(record -> checklist(record, Set.of())));
    }

    @Override
    public Checklist get(UUID clinicId, UUID checklistId) {
        return transactionTemplate.execute(status -> checklist(loadChecklist(clinicId, checklistId), Set.of()));
    }

    @Override
    public List<Template> templates() {
        return transactionTemplate.execute(status -> dsl.selectFrom(PREP_TEMPLATE)
                .orderBy(PREP_TEMPLATE.DISPLAY_ORDER.asc())
                .fetch(template -> new Template(template.getCode(), template.getName(), dsl.selectFrom(PREP_TEMPLATE_SECTION)
                        .where(PREP_TEMPLATE_SECTION.TEMPLATE_ID.eq(template.getId()))
                        .orderBy(PREP_TEMPLATE_SECTION.DISPLAY_ORDER.asc())
                        .fetch(section -> new Section(section.getId(), section.getTitle(), dsl.selectFrom(PREP_TEMPLATE_ITEM)
                                .where(PREP_TEMPLATE_ITEM.SECTION_ID.eq(section.getId()))
                                .orderBy(PREP_TEMPLATE_ITEM.DISPLAY_ORDER.asc())
                                .fetch(item -> new Item(item.getId(), item.getName(), false)))))));
    }

    @Override
    public Checklist importTemplate(UUID clinicId, Actor actor, String templateCode) {
        requireEmployee(clinicId, actor);
        return transactionTemplate.execute(status -> {
            var template = dsl.selectFrom(PREP_TEMPLATE).where(PREP_TEMPLATE.CODE.eq(templateCode)).fetchOne();
            if (template == null) {
                throw missing();
            }
            var request = new ChecklistRequest(template.getName(), dsl.selectFrom(PREP_TEMPLATE_SECTION)
                    .where(PREP_TEMPLATE_SECTION.TEMPLATE_ID.eq(template.getId()))
                    .orderBy(PREP_TEMPLATE_SECTION.DISPLAY_ORDER.asc())
                    .fetch(section -> new SectionRequest(section.getTitle(), dsl.selectFrom(PREP_TEMPLATE_ITEM)
                            .where(PREP_TEMPLATE_ITEM.SECTION_ID.eq(section.getId()))
                            .orderBy(PREP_TEMPLATE_ITEM.DISPLAY_ORDER.asc())
                            .fetch(item -> new ItemRequest(item.getName())))));
            return saveInside(clinicId, null, request);
        });
    }

    @Override
    public Checklist save(UUID clinicId, Actor actor, UUID checklistId, ChecklistRequest request) {
        PrepChecklistService.validate(request);
        return transactionTemplate.execute(status -> { requireEmployee(clinicId, actor); return saveInside(clinicId, checklistId, request); });
    }

    @Override
    public void archive(UUID clinicId, Actor actor, UUID checklistId) {
 transactionTemplate.executeWithoutResult(status -> {
 requireEmployee(clinicId, actor);
            if (dsl.update(PREP_CHECKLIST).set(PREP_CHECKLIST.ARCHIVED_AT, OffsetDateTime.now())
                    .where(PREP_CHECKLIST.ID.eq(checklistId)).and(PREP_CHECKLIST.CLINIC_ID.eq(clinicId))
                    .and(PREP_CHECKLIST.ARCHIVED_AT.isNull()).execute() == 0) {
                throw missing();
            }
        });
    }

    @Override
    public Checklist approve(UUID clinicId, Actor actor, UUID checklistId) {
 return transactionTemplate.execute(status -> {
 requireApprover(clinicId, actor);
            var checklist = loadChecklist(clinicId, checklistId);
            validateStoredStructure(checklistId, checklist.getName());
            dsl.update(PREP_CHECKLIST).set(PREP_CHECKLIST.STATUS, ChecklistStatus.approved)
                    .set(PREP_CHECKLIST.APPROVED_BY, actor.membershipId()).set(PREP_CHECKLIST.APPROVED_AT, OffsetDateTime.now())
                    .where(PREP_CHECKLIST.ID.eq(checklist.getId())).execute();
            return checklist(loadChecklist(clinicId, checklistId), Set.of());
        });
    }

    @Override
    public Checklist unapprove(UUID clinicId, Actor actor, UUID checklistId) {
 return transactionTemplate.execute(status -> {
 requireApprover(clinicId, actor);
            loadChecklist(clinicId, checklistId);
            dsl.update(PREP_CHECKLIST).set(PREP_CHECKLIST.STATUS, ChecklistStatus.draft)
                    .setNull(PREP_CHECKLIST.APPROVED_BY).setNull(PREP_CHECKLIST.APPROVED_AT)
                    .where(PREP_CHECKLIST.ID.eq(checklistId)).and(PREP_CHECKLIST.CLINIC_ID.eq(clinicId)).execute();
            return checklist(loadChecklist(clinicId, checklistId), Set.of());
        });
    }

    @Override
 public Run today(UUID clinicId, Actor actor, UUID checklistId) {
 return transactionTemplate.execute(status -> { requireEmployee(clinicId, actor); return run(clinicId, actor, checklistId); });
    }

    @Override
 public Run toggle(UUID clinicId, Actor actor, UUID checklistId, UUID itemId, boolean checked) {
 return transactionTemplate.execute(status -> { requireEmployee(clinicId, actor);
            var run = runRecord(clinicId, actor, checklistId);
            var itemExists = dsl.fetchExists(dsl.selectOne().from(PREP_ITEM).join(PREP_SECTION).on(PREP_ITEM.SECTION_ID.eq(PREP_SECTION.ID))
                    .where(PREP_ITEM.ID.eq(itemId)).and(PREP_SECTION.CHECKLIST_ID.eq(checklistId)));
            if (!itemExists) {
                throw missing();
            }
            dsl.insertInto(PREP_RUN_ITEM).set(PREP_RUN_ITEM.PREP_RUN_ID, run.getId()).set(PREP_RUN_ITEM.PREP_ITEM_ID, itemId)
                    .set(PREP_RUN_ITEM.CHECKED_AT, checked ? OffsetDateTime.now() : null)
                    .onConflict(PREP_RUN_ITEM.PREP_RUN_ID, PREP_RUN_ITEM.PREP_ITEM_ID)
                    .doUpdate().set(PREP_RUN_ITEM.CHECKED_AT, checked ? OffsetDateTime.now() : null).execute();
            return run(clinicId, actor, checklistId);
        });
    }

    @Override
 public Run reset(UUID clinicId, Actor actor, UUID checklistId) {
 return transactionTemplate.execute(status -> { requireEmployee(clinicId, actor);
            var run = runRecord(clinicId, actor, checklistId);
            dsl.deleteFrom(PREP_RUN_ITEM).where(PREP_RUN_ITEM.PREP_RUN_ID.eq(run.getId())).execute();
            return run(clinicId, actor, checklistId);
        });
    }

    private Checklist saveInside(UUID clinicId, UUID checklistId, ChecklistRequest request) {
        var id = checklistId == null ? UUID.randomUUID() : checklistId;
        if (checklistId == null) {
            dsl.insertInto(PREP_CHECKLIST).set(PREP_CHECKLIST.ID, id).set(PREP_CHECKLIST.CLINIC_ID, clinicId)
                    .set(PREP_CHECKLIST.NAME, request.name()).set(PREP_CHECKLIST.STATUS, ChecklistStatus.draft).execute();
        } else {
            loadChecklist(clinicId, checklistId);
            dsl.update(PREP_CHECKLIST).set(PREP_CHECKLIST.NAME, request.name()).set(PREP_CHECKLIST.STATUS, ChecklistStatus.draft)
                    .setNull(PREP_CHECKLIST.APPROVED_BY).setNull(PREP_CHECKLIST.APPROVED_AT)
                    .where(PREP_CHECKLIST.ID.eq(id)).and(PREP_CHECKLIST.CLINIC_ID.eq(clinicId)).execute();
            dsl.deleteFrom(PREP_SECTION).where(PREP_SECTION.CHECKLIST_ID.eq(id)).execute();
        }
        for (int sectionOrder = 0; sectionOrder < request.sections().size(); sectionOrder++) {
            var section = request.sections().get(sectionOrder);
            var sectionId = UUID.randomUUID();
            dsl.insertInto(PREP_SECTION).set(PREP_SECTION.ID, sectionId).set(PREP_SECTION.CHECKLIST_ID, id)
                    .set(PREP_SECTION.TITLE, section.title()).set(PREP_SECTION.DISPLAY_ORDER, sectionOrder).execute();
            for (int itemOrder = 0; itemOrder < section.items().size(); itemOrder++) {
                dsl.insertInto(PREP_ITEM).set(PREP_ITEM.ID, UUID.randomUUID()).set(PREP_ITEM.SECTION_ID, sectionId)
                        .set(PREP_ITEM.NAME, section.items().get(itemOrder).name()).set(PREP_ITEM.DISPLAY_ORDER, itemOrder).execute();
            }
        }
        return checklist(loadChecklist(clinicId, id), Set.of());
    }

    private Run run(UUID clinicId, Actor actor, UUID checklistId) {
        var run = runRecord(clinicId, actor, checklistId);
        var checked = dsl.select(PREP_RUN_ITEM.PREP_ITEM_ID).from(PREP_RUN_ITEM)
                .where(PREP_RUN_ITEM.PREP_RUN_ID.eq(run.getId())).and(PREP_RUN_ITEM.CHECKED_AT.isNotNull()).fetchSet(PREP_RUN_ITEM.PREP_ITEM_ID);
        var checklist = checklist(loadChecklist(clinicId, checklistId), checked);
        var total = checklist.sections().stream().mapToInt(section -> section.items().size()).sum();
        return new Run(run.getId(), run.getRunDate(), checked.size(), total, checklist.sections());
    }

    private PrepRunRecord runRecord(UUID clinicId, Actor actor, UUID checklistId) {
        var checklist = loadChecklist(clinicId, checklistId);
        if (checklist.getStatus() != ChecklistStatus.approved) {
            throw new IllegalArgumentException("القائمة غير معتمدة");
        }
        var date = LocalDate.now();
        var existing = dsl.selectFrom(PREP_RUN).where(PREP_RUN.CLINIC_ID.eq(clinicId)).and(PREP_RUN.CHECKLIST_ID.eq(checklistId))
                .and(PREP_RUN.EMPLOYEE_ID.eq(actor.employeeId())).and(PREP_RUN.RUN_DATE.eq(date)).fetchOne();
        if (existing != null) {
            return existing;
        }
        dsl.insertInto(PREP_RUN).set(PREP_RUN.CLINIC_ID, clinicId).set(PREP_RUN.CHECKLIST_ID, checklistId)
                .set(PREP_RUN.EMPLOYEE_ID, actor.employeeId()).set(PREP_RUN.RUN_DATE, date)
                .onConflict(PREP_RUN.CLINIC_ID, PREP_RUN.CHECKLIST_ID, PREP_RUN.EMPLOYEE_ID, PREP_RUN.RUN_DATE).doNothing().execute();
        return dsl.selectFrom(PREP_RUN).where(PREP_RUN.CLINIC_ID.eq(clinicId)).and(PREP_RUN.CHECKLIST_ID.eq(checklistId))
                .and(PREP_RUN.EMPLOYEE_ID.eq(actor.employeeId())).and(PREP_RUN.RUN_DATE.eq(date)).fetchOne();
    }

    private PrepChecklistRecord loadChecklist(UUID clinicId, UUID checklistId) {
        var checklist = dsl.selectFrom(PREP_CHECKLIST).where(PREP_CHECKLIST.ID.eq(checklistId)).and(PREP_CHECKLIST.CLINIC_ID.eq(clinicId))
                .and(PREP_CHECKLIST.ARCHIVED_AT.isNull()).fetchOne();
        if (checklist == null) {
            throw missing();
        }
        return checklist;
    }

    private Checklist checklist(PrepChecklistRecord record, Set<UUID> checked) {
        return new Checklist(record.getId(), record.getName(), record.getStatus().getLiteral(), record.getApprovedBy(),
                dsl.selectFrom(PREP_SECTION).where(PREP_SECTION.CHECKLIST_ID.eq(record.getId())).orderBy(PREP_SECTION.DISPLAY_ORDER.asc())
                        .fetch(section -> new Section(section.getId(), section.getTitle(), dsl.selectFrom(PREP_ITEM)
                                .where(PREP_ITEM.SECTION_ID.eq(section.getId())).orderBy(PREP_ITEM.DISPLAY_ORDER.asc())
                                .fetch(item -> new Item(item.getId(), item.getName(), checked.contains(item.getId()))))));
    }

    private void validateStoredStructure(UUID checklistId, String name) {
        PrepChecklistService.validate(new ChecklistRequest(name, dsl.selectFrom(PREP_SECTION)
                .where(PREP_SECTION.CHECKLIST_ID.eq(checklistId)).orderBy(PREP_SECTION.DISPLAY_ORDER.asc())
                .fetch(section -> new SectionRequest(section.getTitle(), dsl.selectFrom(PREP_ITEM)
                        .where(PREP_ITEM.SECTION_ID.eq(section.getId())).orderBy(PREP_ITEM.DISPLAY_ORDER.asc())
                        .fetch(item -> new ItemRequest(item.getName()))))));
    }

    private void requireEmployee(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null || actor.employeeId() == null) {
            throw new IllegalArgumentException("غير مصرح");
        }
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP)
                .where(MEMBERSHIP.ID.eq(actor.membershipId())).and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.EMPLOYEE_ID.eq(actor.employeeId())).and(MEMBERSHIP.STATUS.eq(MembershipStatus.active)))) {
            throw new IllegalArgumentException("غير مصرح");
        }
    }

    private void requireApprover(UUID clinicId, Actor actor) {
        requireEmployee(clinicId, actor);
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP).join(ROLE).on(MEMBERSHIP.ROLE_ID.eq(ROLE.ID))
                .where(MEMBERSHIP.ID.eq(actor.membershipId())).and(ROLE.CODE.in("owner", "manager")))) {
            throw new IllegalArgumentException("غير مصرح");
        }
    }

    private IllegalArgumentException missing() {
        return new IllegalArgumentException("القائمة غير موجودة");
    }
}
