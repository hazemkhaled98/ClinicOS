package com.clinicos.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.shared.jooq.enums.LocationKind;

/**
 * Inventory foundation: the item catalogue, the append-only stock ledger
 * (on-hand is always sum of {@code stock_movement.qty_delta} grouped by
 * item+location, never a column), and the manager approval queue for edits and
 * deletions of existing items (BR-G26). Every method runs inside the clinic
 * bound to the current thread's {@code TenantContext}; callers must guarantee
 * a tenant is bound before invoking (TenantSessionFilter does for HTTP
 * requests, tests seed it explicitly).
 */
public interface InventoryService {

    /** The actor making a call: membership + the session's role code. */
    record Actor(UUID membershipId, String roleCode) {
    }

    /** Proposed or applied editable fields of an inventory item. */
    record ItemRequest(String name, String uom, Integer unitsPerPack, BigDecimal unitCost,
            Integer storeAlert, Integer trayAlert) {
    }

    /** An inventory item with its live on-hand at both fixed locations. */
    record Item(UUID id, String name, String uom, Integer unitsPerPack, BigDecimal unitCost,
            Integer storeAlert, Integer trayAlert, BigDecimal onHandStore, BigDecimal onHandTray,
            boolean belowStoreAlert, boolean belowTrayAlert, OffsetDateTime archivedAt) {
    }

    /** One item's stock figure filtered to a single location (tray / issue screens). */
    record StockLine(UUID id, String name, String uom, BigDecimal unitCost,
            BigDecimal onHand, Integer alert, boolean belowAlert) {
    }

    /** A change request awaiting manager approval (BR-G26). */
    record ChangeRequest(UUID id, String kind, UUID itemId, String itemName,
            ItemRequest proposed, OffsetDateTime requestedAt, String status) {
    }

    /** Result of an issue: what was asked, what was actually issued, and whether it clamped. */
    record IssueResult(UUID itemId, LocationKind location, BigDecimal requestedQty,
            BigDecimal issuedQty, boolean clamped) {
    }

    /** One append-only ledger row with the item and actor display info joined in. */
    record MovementEntry(UUID id, String itemName, String uom, LocationKind location,
            BigDecimal qtyDelta, String reason, String actorName, OffsetDateTime createdAt) {
    }

    /** Full catalogue; excludes archived items unless {@code includeArchived}. */
    List<Item> items(UUID clinicId, boolean includeArchived);

    /** The same on-hand figures, filtered to one location. */
    List<StockLine> stock(UUID clinicId, LocationKind location);

    /** Direct-insert a new item (creating is not stock-affecting, so no approval gate). */
    Item createItem(UUID clinicId, Actor actor, ItemRequest request);

    /** BR-G26 gate: file an edit/delete request for an existing item; nothing is applied yet. */
    ChangeRequest requestItemChange(UUID clinicId, Actor actor, UUID itemId,
            ChangeRequestKind kind, ItemRequest proposedValues);

    /** The pending change-request queue, newest first. */
    List<ChangeRequest> pendingChangeRequests(UUID clinicId);

    /** Manager-only decision: approve applies the change / soft-deletes; reject discards. */
    ChangeRequest applyItemChange(UUID clinicId, Actor actor, UUID requestId, boolean approve);

    /** Issue stock at a location, clamped to what is actually on hand (UC-008 A3). */
    IssueResult issue(UUID clinicId, Actor actor, UUID itemId, LocationKind location,
            BigDecimal requestedQty);

    /** Move stock between the two locations (two ledger rows, clamped to the source). */
    void transfer(UUID clinicId, Actor actor, UUID itemId, LocationKind from, LocationKind to,
            BigDecimal qty);

    /** Record a stocktake correction as a single compensating 'count' ledger row. */
    void adjustCount(UUID clinicId, Actor actor, UUID itemId, LocationKind location,
            BigDecimal newCount);

    /** Newest-first ledger for the audit-log screen; a simple limit-based page. */
    List<MovementEntry> ledger(UUID clinicId, int limit);
}