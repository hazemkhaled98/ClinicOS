package com.clinicos.procedures.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.InventoryChangeRequest.INVENTORY_CHANGE_REQUEST;
import static com.clinicos.shared.jooq.tables.InventoryItem.INVENTORY_ITEM;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Procedure.PROCEDURE;
import static com.clinicos.shared.jooq.tables.ProcedureBom.PROCEDURE_BOM;
import static com.clinicos.shared.jooq.tables.ProcedureCase.PROCEDURE_CASE;
import static com.clinicos.shared.jooq.tables.ProcedureCaseItem.PROCEDURE_CASE_ITEM;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.procedures.ProceduresService;
import com.clinicos.procedures.ProceduresService.BomLine;
import com.clinicos.procedures.ProceduresService.BomLineRequest;
import com.clinicos.procedures.ProceduresService.CaseDraft;
import com.clinicos.procedures.ProceduresService.CaseItem;
import com.clinicos.procedures.ProceduresService.CaseItemRequest;
import com.clinicos.procedures.ProceduresService.CaseRecord;
import com.clinicos.procedures.ProceduresService.PendingChange;
import com.clinicos.procedures.ProceduresService.Procedure;
import com.clinicos.procedures.ProceduresService.ProcedureRequest;
import com.clinicos.shared.jooq.enums.ChangeRequestEntity;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.shared.jooq.enums.ChangeRequestStatus;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.enums.MovementReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DefaultProceduresService implements ProceduresService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final InventoryService inventoryService;

    public DefaultProceduresService(DSLContext dsl, TransactionTemplate transactionTemplate,
            InventoryService inventoryService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.inventoryService = inventoryService;
    }

    @Override
    public List<Procedure> procedures(UUID clinicId, boolean includeArchived) {
        return transactionTemplate.execute(status -> dsl.selectFrom(PROCEDURE)
                .where(PROCEDURE.CLINIC_ID.eq(clinicId))
                .and(includeArchived ? PROCEDURE.CLINIC_ID.isNotNull() : PROCEDURE.ARCHIVED_AT.isNull())
                .orderBy(PROCEDURE.NAME.asc())
                .fetch(r -> loadProcedure(clinicId, r.get(PROCEDURE.ID))));
    }

    @Override
    public Procedure createProcedure(UUID clinicId, Actor actor, ProcedureRequest request) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            validateProcedure(request);
            var id = UUID.randomUUID();
            dsl.insertInto(PROCEDURE)
                    .set(PROCEDURE.ID, id)
                    .set(PROCEDURE.CLINIC_ID, clinicId)
                    .set(PROCEDURE.NAME, request.name().trim())
                    .set(PROCEDURE.PRICE, request.price())
                    .set(PROCEDURE.LABOR_COST, request.laborCost())
                    .set(PROCEDURE.DOCTOR_FEE, request.doctorFee())
                    .execute();
            replaceBom(clinicId, id, request.bom());
            return loadProcedure(clinicId, id);
        });
    }

    @Override
    public UUID requestProcedureChange(UUID clinicId, Actor actor, UUID procedureId,
            ChangeRequestKind kind, ProcedureRequest proposed) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            requireProcedure(clinicId, procedureId);
            if (kind == null || kind == ChangeRequestKind.create) throw missing("نوع الطلب غير صالح");
            var payload = kind == ChangeRequestKind.edit ? serialize(proposed) : null;
            return insertChange(clinicId, actor, kind, ChangeRequestEntity.procedure, procedureId, null, payload);
        });
    }

    @Override
    public UUID requestProcedureBomChange(UUID clinicId, Actor actor, UUID procedureId,
            List<BomLineRequest> proposed) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            requireProcedure(clinicId, procedureId);
            if (proposed == null) throw missing("الوصفة المقترحة مطلوبة");
            return insertChange(clinicId, actor, ChangeRequestKind.edit, ChangeRequestEntity.procedure_bom,
                    procedureId, null, serialize(proposed));
        });
    }

    @Override
    public CaseRecord recordCase(UUID clinicId, Actor actor, CaseDraft draft) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            var procedure = loadProcedure(clinicId, draft == null ? null : draft.procedureId());
            requireEmployee(clinicId, draft == null ? null : draft.employeeId());
            var items = draft.items() == null || draft.items().isEmpty()
                    ? procedure.bom().stream().map(line -> new CaseItemRequest(line.itemId(), line.qty())).toList()
                    : draft.items();
            if (items.isEmpty()) throw missing("اختر صنفاً واحداً على الأقل");
            var caseId = UUID.randomUUID();
            dsl.insertInto(PROCEDURE_CASE)
                    .set(PROCEDURE_CASE.ID, caseId)
                    .set(PROCEDURE_CASE.CLINIC_ID, clinicId)
                    .set(PROCEDURE_CASE.PROCEDURE_ID, draft.procedureId())
                    .set(PROCEDURE_CASE.EMPLOYEE_ID, draft.employeeId())
                    .set(PROCEDURE_CASE.DOCTOR_NAME, blankToNull(draft.doctorName()))
                    .set(PROCEDURE_CASE.PATIENT_REF, blankToNull(draft.patientRef()))
                    .execute();
            var recorded = issueCaseItems(clinicId, actor, caseId, items);
            if (recorded.isEmpty()) throw missing("لا يوجد رصيد كافٍ لتسجيل الإجراء");
            for (var item : recorded) {
                dsl.insertInto(PROCEDURE_CASE_ITEM)
                        .set(PROCEDURE_CASE_ITEM.ID, UUID.randomUUID())
                        .set(PROCEDURE_CASE_ITEM.PROCEDURE_CASE_ID, caseId)
                        .set(PROCEDURE_CASE_ITEM.ITEM_ID, item.itemId())
                        .set(PROCEDURE_CASE_ITEM.QTY, item.qty())
                        .set(PROCEDURE_CASE_ITEM.UNIT_COST_AT_TIME, item.unitCostAtTime())
                        .execute();
            }
            return loadCase(clinicId, caseId);
        });
    }

    @Override
    public List<CaseRecord> cases(UUID clinicId, UUID employeeId, LocalDate from, LocalDate to) {
        return transactionTemplate.execute(status -> {
            var query = dsl.select(PROCEDURE_CASE.ID).from(PROCEDURE_CASE)
                    .where(PROCEDURE_CASE.CLINIC_ID.eq(clinicId));
            if (employeeId != null) query = query.and(PROCEDURE_CASE.EMPLOYEE_ID.eq(employeeId));
            if (from != null) query = query.and(PROCEDURE_CASE.PERFORMED_AT.ge(from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)));
            if (to != null) query = query.and(PROCEDURE_CASE.PERFORMED_AT.lt(to.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)));
            return query.orderBy(PROCEDURE_CASE.PERFORMED_AT.desc()).fetch(PROCEDURE_CASE.ID).stream()
                    .map(id -> loadCase(clinicId, id)).toList();
        });
    }

    @Override
    public UUID requestCaseChange(UUID clinicId, Actor actor, UUID caseId, ChangeRequestKind kind,
            List<CaseItemRequest> proposedItems) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            requireCase(clinicId, caseId);
            if (kind == null || kind == ChangeRequestKind.create || proposedItems == null) {
                throw missing("بيانات تعديل الإجراء غير صالحة");
            }
            return insertChange(clinicId, actor, kind, ChangeRequestEntity.procedure_case, null, caseId,
                    serialize(proposedItems));
        });
    }

    @Override
    public List<PendingChange> pendingChanges(UUID clinicId) {
        return transactionTemplate.execute(status -> dsl.selectFrom(INVENTORY_CHANGE_REQUEST)
                .where(INVENTORY_CHANGE_REQUEST.CLINIC_ID.eq(clinicId))
                .and(INVENTORY_CHANGE_REQUEST.STATUS.eq(ChangeRequestStatus.pending))
                .and(INVENTORY_CHANGE_REQUEST.ENTITY.ne(ChangeRequestEntity.item))
                .orderBy(INVENTORY_CHANGE_REQUEST.REQUESTED_AT.desc())
                .fetch(r -> new PendingChange(r.get(INVENTORY_CHANGE_REQUEST.ID),
                        r.get(INVENTORY_CHANGE_REQUEST.ENTITY).getLiteral(),
                        r.get(INVENTORY_CHANGE_REQUEST.KIND).getLiteral(),
                        r.get(INVENTORY_CHANGE_REQUEST.ENTITY).getLiteral(), "طلب تغيير بانتظار الموافقة",
                        r.get(INVENTORY_CHANGE_REQUEST.REQUESTED_AT))));
    }

    @Override
    public PendingChange decideChange(UUID clinicId, Actor actor, UUID requestId, boolean approve) {
        return transactionTemplate.execute(status -> {
            requireManager(clinicId, actor);
            var row = dsl.selectFrom(INVENTORY_CHANGE_REQUEST)
                    .where(INVENTORY_CHANGE_REQUEST.ID.eq(requestId))
                    .and(INVENTORY_CHANGE_REQUEST.CLINIC_ID.eq(clinicId))
                    .fetchOne();
            if (row == null || row.getStatus() != ChangeRequestStatus.pending) throw missing("طلب الموافقة غير موجود أو تمت معالجته");
            if (approve) {
                switch (row.getEntity()) {
                    case procedure -> applyProcedureChange(clinicId, row.getProcedureId(), row.getKind(), row.getPayload());
                    case procedure_bom -> replaceBom(clinicId, row.getProcedureId(), parseBom(row.getPayload()));
                    case procedure_case -> applyCaseChange(clinicId, actor, row.getProcedureCaseId(), row.getPayload());
                    default -> throw missing("نوع طلب الموافقة غير مدعوم");
                }
            }
            dsl.update(INVENTORY_CHANGE_REQUEST)
                    .set(INVENTORY_CHANGE_REQUEST.STATUS, approve ? ChangeRequestStatus.approved : ChangeRequestStatus.rejected)
                    .set(INVENTORY_CHANGE_REQUEST.DECIDED_BY, actor.membershipId())
                    .set(INVENTORY_CHANGE_REQUEST.DECIDED_AT, OffsetDateTime.now())
                    .where(INVENTORY_CHANGE_REQUEST.ID.eq(requestId)).execute();
            return new PendingChange(requestId, row.getEntity().getLiteral(), row.getKind().getLiteral(),
                    row.getEntity().getLiteral(), approve ? "تمت الموافقة" : "تم الرفض", row.getRequestedAt());
        });
    }

    private List<CaseItem> issueCaseItems(UUID clinicId, Actor actor, UUID caseId, List<CaseItemRequest> requested) {
        var result = new ArrayList<CaseItem>();
        for (var request : requested) {
            if (request == null || request.itemId() == null || request.qty() == null || request.qty().signum() <= 0) {
                throw missing("بيانات صنف الإجراء غير صالحة");
            }
            var item = dsl.select(INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM, INVENTORY_ITEM.UNIT_COST)
                    .from(INVENTORY_ITEM).where(INVENTORY_ITEM.ID.eq(request.itemId()))
                    .and(INVENTORY_ITEM.CLINIC_ID.eq(clinicId)).fetchOne();
            if (item == null) throw missing("الصنف غير موجود");
            var issuedQty = issueCaseQuantity(clinicId, actor, request.itemId(), request.qty(), caseId);
            if (issuedQty.signum() > 0) {
                result.add(new CaseItem(request.itemId(), item.get(INVENTORY_ITEM.NAME), item.get(INVENTORY_ITEM.UOM),
                        issuedQty, item.get(INVENTORY_ITEM.UNIT_COST)));
            }
        }
        return result;
    }

    private BigDecimal issueCaseQuantity(UUID clinicId, Actor actor, UUID itemId, BigDecimal requestedQty, UUID caseId) {
        var trayIssued = inventoryService.issueFor(clinicId, actor, itemId, LocationKind.tray, requestedQty,
                "procedure_case", caseId).issuedQty();
        var remaining = requestedQty.subtract(trayIssued);
        if (remaining.signum() <= 0) return trayIssued;
        return trayIssued.add(inventoryService.issueFor(clinicId, actor, itemId, LocationKind.store, remaining,
                "procedure_case", caseId).issuedQty());
    }

    private void applyCaseChange(UUID clinicId, Actor actor, UUID caseId, JSONB payload) {
        var current = dsl.select(PROCEDURE_CASE_ITEM.ITEM_ID, PROCEDURE_CASE_ITEM.QTY)
                .from(PROCEDURE_CASE_ITEM).where(PROCEDURE_CASE_ITEM.PROCEDURE_CASE_ID.eq(caseId)).fetch();
        var currentMap = new HashMap<UUID, BigDecimal>();
        current.forEach(r -> currentMap.put(r.get(PROCEDURE_CASE_ITEM.ITEM_ID), r.get(PROCEDURE_CASE_ITEM.QTY)));
        var proposed = parseCaseItems(payload);
        var proposedMap = new HashMap<UUID, BigDecimal>();
        proposed.forEach(i -> proposedMap.merge(i.itemId(), i.qty(), BigDecimal::add));
        var ids = new java.util.HashSet<UUID>(currentMap.keySet());
        ids.addAll(proposedMap.keySet());
        var appliedMap = new HashMap<>(currentMap);
        for (var itemId : ids) {
            var delta = proposedMap.getOrDefault(itemId, BigDecimal.ZERO).subtract(currentMap.getOrDefault(itemId, BigDecimal.ZERO));
            if (delta.signum() > 0) {
                var issuedQty = issueCaseQuantity(clinicId, actor, itemId, delta, caseId);
                appliedMap.put(itemId, currentMap.getOrDefault(itemId, BigDecimal.ZERO).add(issuedQty));
            } else if (delta.signum() < 0) {
                inventoryService.adjustFor(clinicId, actor, itemId, LocationKind.tray, delta.negate(),
                        MovementReason.adjustment, "procedure_case", caseId);
                appliedMap.put(itemId, proposedMap.getOrDefault(itemId, BigDecimal.ZERO));
            }
        }
        dsl.deleteFrom(PROCEDURE_CASE_ITEM).where(PROCEDURE_CASE_ITEM.PROCEDURE_CASE_ID.eq(caseId)).execute();
        for (var entry : appliedMap.entrySet()) {
            if (entry.getValue().signum() > 0) {
                var cost = dsl.select(INVENTORY_ITEM.UNIT_COST).from(INVENTORY_ITEM)
                        .where(INVENTORY_ITEM.ID.eq(entry.getKey())).fetchOne(INVENTORY_ITEM.UNIT_COST);
                dsl.insertInto(PROCEDURE_CASE_ITEM).set(PROCEDURE_CASE_ITEM.ID, UUID.randomUUID())
                        .set(PROCEDURE_CASE_ITEM.PROCEDURE_CASE_ID, caseId).set(PROCEDURE_CASE_ITEM.ITEM_ID, entry.getKey())
                        .set(PROCEDURE_CASE_ITEM.QTY, entry.getValue()).set(PROCEDURE_CASE_ITEM.UNIT_COST_AT_TIME, cost).execute();
            }
        }
    }

    private void applyProcedureChange(UUID clinicId, UUID procedureId, ChangeRequestKind kind, JSONB payload) {
        requireProcedure(clinicId, procedureId);
        if (kind == ChangeRequestKind.delete) {
            dsl.update(PROCEDURE).set(PROCEDURE.ARCHIVED_AT, OffsetDateTime.now())
                    .where(PROCEDURE.ID.eq(procedureId)).and(PROCEDURE.CLINIC_ID.eq(clinicId)).execute();
            return;
        }
        var node = parse(payload);
        dsl.update(PROCEDURE).set(PROCEDURE.NAME, node.get("name").asText())
                .set(PROCEDURE.PRICE, node.get("price").decimalValue())
                .set(PROCEDURE.LABOR_COST, node.get("laborCost").decimalValue())
                .set(PROCEDURE.DOCTOR_FEE, node.get("doctorFee").decimalValue())
                .where(PROCEDURE.ID.eq(procedureId)).and(PROCEDURE.CLINIC_ID.eq(clinicId)).execute();
    }

    private void replaceBom(UUID clinicId, UUID procedureId, List<BomLineRequest> lines) {
        requireProcedure(clinicId, procedureId);
        dsl.deleteFrom(PROCEDURE_BOM).where(PROCEDURE_BOM.PROCEDURE_ID.eq(procedureId)).execute();
        for (var line : lines == null ? List.<BomLineRequest>of() : lines) {
            if (line.itemId() == null || line.qty() == null || line.qty().signum() <= 0) throw missing("بيانات الوصفة غير صالحة");
            requireItem(clinicId, line.itemId());
            dsl.insertInto(PROCEDURE_BOM).set(PROCEDURE_BOM.PROCEDURE_ID, procedureId)
                    .set(PROCEDURE_BOM.ITEM_ID, line.itemId()).set(PROCEDURE_BOM.QTY, line.qty()).execute();
        }
    }

    private Procedure loadProcedure(UUID clinicId, UUID id) {
        var row = dsl.selectFrom(PROCEDURE).where(PROCEDURE.ID.eq(id)).and(PROCEDURE.CLINIC_ID.eq(clinicId)).fetchOne();
        if (row == null) throw missing("الإجراء غير موجود");
        var bom = dsl.select(PROCEDURE_BOM.ITEM_ID, PROCEDURE_BOM.QTY, INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM, INVENTORY_ITEM.UNIT_COST)
                .from(PROCEDURE_BOM).join(INVENTORY_ITEM).on(PROCEDURE_BOM.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .where(PROCEDURE_BOM.PROCEDURE_ID.eq(id)).fetch(r -> new BomLine(r.get(PROCEDURE_BOM.ITEM_ID),
                        r.get(INVENTORY_ITEM.NAME), r.get(INVENTORY_ITEM.UOM), r.get(PROCEDURE_BOM.QTY), r.get(INVENTORY_ITEM.UNIT_COST)));
        var estimated = bom.stream().map(b -> b.qty().multiply(b.unitCost())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Procedure(row.get(PROCEDURE.ID), row.get(PROCEDURE.NAME), row.get(PROCEDURE.PRICE), row.get(PROCEDURE.LABOR_COST),
                row.get(PROCEDURE.DOCTOR_FEE), estimated, bom, row.get(PROCEDURE.ARCHIVED_AT) != null);
    }

    private CaseRecord loadCase(UUID clinicId, UUID id) {
        var row = dsl.select(PROCEDURE_CASE.ID, PROCEDURE_CASE.PROCEDURE_ID, PROCEDURE_CASE.EMPLOYEE_ID,
                PROCEDURE_CASE.DOCTOR_NAME, PROCEDURE_CASE.PATIENT_REF, PROCEDURE_CASE.PERFORMED_AT,
                PROCEDURE.NAME, EMPLOYEE.NAME, PROCEDURE.LABOR_COST, PROCEDURE.DOCTOR_FEE, PROCEDURE.PRICE)
                .from(PROCEDURE_CASE).join(PROCEDURE).on(PROCEDURE_CASE.PROCEDURE_ID.eq(PROCEDURE.ID))
                .join(EMPLOYEE).on(PROCEDURE_CASE.EMPLOYEE_ID.eq(EMPLOYEE.ID))
                .where(PROCEDURE_CASE.ID.eq(id)).and(PROCEDURE_CASE.CLINIC_ID.eq(clinicId)).fetchOne();
        if (row == null) throw missing("الإجراء المسجل غير موجود");
        var items = dsl.select(PROCEDURE_CASE_ITEM.ITEM_ID, PROCEDURE_CASE_ITEM.QTY, PROCEDURE_CASE_ITEM.UNIT_COST_AT_TIME,
                INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM).from(PROCEDURE_CASE_ITEM)
                .join(INVENTORY_ITEM).on(PROCEDURE_CASE_ITEM.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .where(PROCEDURE_CASE_ITEM.PROCEDURE_CASE_ID.eq(id)).fetch(r -> new CaseItem(r.get(PROCEDURE_CASE_ITEM.ITEM_ID),
                        r.get(INVENTORY_ITEM.NAME), r.get(INVENTORY_ITEM.UOM), r.get(PROCEDURE_CASE_ITEM.QTY),
                        r.get(PROCEDURE_CASE_ITEM.UNIT_COST_AT_TIME)));
        var material = items.stream().map(i -> i.qty().multiply(i.unitCostAtTime())).reduce(BigDecimal.ZERO, BigDecimal::add);
        var total = material.add(row.get(PROCEDURE.LABOR_COST)).add(row.get(PROCEDURE.DOCTOR_FEE));
        return new CaseRecord(row.get(PROCEDURE_CASE.ID), row.get(PROCEDURE_CASE.PROCEDURE_ID), row.get(PROCEDURE.NAME),
                row.get(PROCEDURE_CASE.EMPLOYEE_ID), row.get(EMPLOYEE.NAME), row.get(PROCEDURE_CASE.DOCTOR_NAME),
                row.get(PROCEDURE_CASE.PATIENT_REF), row.get(PROCEDURE_CASE.PERFORMED_AT), material,
                row.get(PROCEDURE.LABOR_COST), row.get(PROCEDURE.DOCTOR_FEE), total, row.get(PROCEDURE.PRICE),
                row.get(PROCEDURE.PRICE).subtract(total), items);
    }

    private UUID insertChange(UUID clinicId, Actor actor, ChangeRequestKind kind, ChangeRequestEntity entity,
            UUID procedureId, UUID caseId, JSONB payload) {
        var id = UUID.randomUUID();
        dsl.insertInto(INVENTORY_CHANGE_REQUEST).set(INVENTORY_CHANGE_REQUEST.ID, id)
                .set(INVENTORY_CHANGE_REQUEST.CLINIC_ID, clinicId).set(INVENTORY_CHANGE_REQUEST.KIND, kind)
                .set(INVENTORY_CHANGE_REQUEST.ENTITY, entity).set(INVENTORY_CHANGE_REQUEST.PROCEDURE_ID, procedureId)
                .set(INVENTORY_CHANGE_REQUEST.PROCEDURE_CASE_ID, caseId).set(INVENTORY_CHANGE_REQUEST.PAYLOAD, payload)
                .set(INVENTORY_CHANGE_REQUEST.REQUESTED_BY, actor.membershipId()).set(INVENTORY_CHANGE_REQUEST.STATUS, ChangeRequestStatus.pending).execute();
        return id;
    }

    private List<BomLineRequest> parseBom(JSONB payload) {
        var result = new ArrayList<BomLineRequest>();
        for (var node : parse(payload)) result.add(new BomLineRequest(UUID.fromString(node.get("itemId").asText()), node.get("qty").decimalValue()));
        return result;
    }

    private List<CaseItemRequest> parseCaseItems(JSONB payload) {
        var result = new ArrayList<CaseItemRequest>();
        for (var node : parse(payload)) result.add(new CaseItemRequest(UUID.fromString(node.get("itemId").asText()), node.get("qty").decimalValue()));
        return result;
    }

    private JSONB serialize(Object value) {
        try { return JSONB.valueOf(JSON.writeValueAsString(value)); }
        catch (Exception e) { throw missing("بيانات الطلب غير صالحة", e); }
    }

    private JsonNode parse(JSONB payload) {
        try { return JSON.readTree(payload.data()); }
        catch (Exception e) { throw missing("بيانات الطلب غير صالحة", e); }
    }

    private void validateProcedure(ProcedureRequest request) {
        if (request == null || blankToNull(request.name()) == null || request.price() == null || request.laborCost() == null || request.doctorFee() == null) {
            throw missing("اسم الإجراء والأسعار مطلوبة");
        }
    }

    private com.clinicos.shared.jooq.tables.records.ProcedureRecord requireProcedure(UUID clinicId, UUID id) {
        var row = id == null ? null : dsl.selectFrom(PROCEDURE).where(PROCEDURE.ID.eq(id)).and(PROCEDURE.CLINIC_ID.eq(clinicId)).fetchOne();
        if (row == null) throw missing("الإجراء غير موجود");
        return row;
    }

    private void requireCase(UUID clinicId, UUID id) {
        if (id == null || !dsl.fetchExists(dsl.selectOne().from(PROCEDURE_CASE).where(PROCEDURE_CASE.ID.eq(id)).and(PROCEDURE_CASE.CLINIC_ID.eq(clinicId)))) throw missing("الإجراء المسجل غير موجود");
    }

    private void requireItem(UUID clinicId, UUID id) {
        if (id == null || !dsl.fetchExists(dsl.selectOne().from(INVENTORY_ITEM).where(INVENTORY_ITEM.ID.eq(id)).and(INVENTORY_ITEM.CLINIC_ID.eq(clinicId)))) throw missing("الصنف غير موجود");
    }

    private void requireEmployee(UUID clinicId, UUID id) {
        if (id == null || !dsl.fetchExists(dsl.selectOne().from(EMPLOYEE).where(EMPLOYEE.ID.eq(id)).and(EMPLOYEE.CLINIC_ID.eq(clinicId)))) throw missing("الموظف غير موجود");
    }

    private void requireActiveActor(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null || !dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP)
                .where(MEMBERSHIP.ID.eq(actor.membershipId())).and(MEMBERSHIP.CLINIC_ID.eq(clinicId)).and(MEMBERSHIP.STATUS.eq(MembershipStatus.active)))) throw missing("غير مصرح");
    }

    private void requireManager(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null || !dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP).join(ROLE).on(MEMBERSHIP.ROLE_ID.eq(ROLE.ID))
                .where(MEMBERSHIP.ID.eq(actor.membershipId())).and(MEMBERSHIP.CLINIC_ID.eq(clinicId)).and(MEMBERSHIP.STATUS.eq(MembershipStatus.active)).and(ROLE.CODE.in("owner", "manager")))) throw missing("غير مصرح — تتطلب صلاحيات مدير");
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private IllegalArgumentException missing(String message) { return new IllegalArgumentException(message); }
    private IllegalArgumentException missing(String message, Throwable cause) { return new IllegalArgumentException(message, cause); }
}
