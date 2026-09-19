package com.clinicos.procedures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;

public interface ProceduresService {

    record BomLine(UUID itemId, String itemName, String uom, BigDecimal qty, BigDecimal unitCost) {
    }

    record Procedure(UUID id, String name, BigDecimal price, BigDecimal laborCost, BigDecimal doctorFee,
            BigDecimal estimatedMaterialCost, List<BomLine> bom, boolean archived) {
    }

    record BomLineRequest(UUID itemId, BigDecimal qty) {
    }

    record ProcedureRequest(String name, BigDecimal price, BigDecimal laborCost, BigDecimal doctorFee,
            List<BomLineRequest> bom) {
    }

    record CaseItemRequest(UUID itemId, BigDecimal qty) {
    }

    record CaseDraft(UUID procedureId, UUID employeeId, String doctorName, String patientRef,
            List<CaseItemRequest> items) {
    }

    record CaseItem(UUID itemId, String itemName, String uom, BigDecimal qty, BigDecimal unitCostAtTime) {
    }

    record CaseRecord(UUID id, UUID procedureId, String procedureName, UUID employeeId, String employeeName,
            String doctorName, String patientRef, OffsetDateTime performedAt, BigDecimal materialCost,
            BigDecimal laborCost, BigDecimal doctorFee, BigDecimal totalCost, BigDecimal price,
            BigDecimal margin, List<CaseItem> items) {
    }

    record PendingChange(UUID id, String entity, String kind, String title, String summary,
            OffsetDateTime requestedAt) {
    }

    List<Procedure> procedures(UUID clinicId, boolean includeArchived);

    Procedure createProcedure(UUID clinicId, Actor actor, ProcedureRequest request);

    UUID requestProcedureChange(UUID clinicId, Actor actor, UUID procedureId,
            ChangeRequestKind kind, ProcedureRequest proposed);

    UUID requestProcedureBomChange(UUID clinicId, Actor actor, UUID procedureId,
            List<BomLineRequest> proposed);

    CaseRecord recordCase(UUID clinicId, Actor actor, CaseDraft draft);

    List<CaseRecord> cases(UUID clinicId, UUID employeeId, LocalDate from, LocalDate to);

    UUID requestCaseChange(UUID clinicId, Actor actor, UUID caseId, ChangeRequestKind kind,
            List<CaseItemRequest> proposedItems);

    List<PendingChange> pendingChanges(UUID clinicId);

    PendingChange decideChange(UUID clinicId, Actor actor, UUID requestId, boolean approve);
}
