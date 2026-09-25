package com.clinicos.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.shared.jooq.enums.PoStatus;

/**
 * Purchasing: supplier directory, shortage-driven ordering, receiving with a
 * clinic-configured invoice photo requirement (UC-008 A4), and supplier returns under the BR-G27
 * receivable ceiling. Runs inside the clinic bound to the current thread's
 * {@code TenantContext}; callers must guarantee a tenant is bound first.
 */
public interface PurchasingService {

    /** An item on (or below) its store alert, with ordering context. */
    record Shortage(UUID itemId, String name, String uom, BigDecimal onHandStore,
            Integer storeAlert, UUID preferredSupplierId, String preferredSupplierName,
            BigDecimal lastUnitCost, boolean noPrice) {
    }

    /** A supplier row plus the latest delivery against it (derived). */
    record Supplier(UUID id, String name, String contact, String whatsapp, Integer leadDays,
            BigDecimal rating, OffsetDateTime lastDeliveryAt, boolean archived) {
    }

    /** Editable supplier fields; {@code archived} sets/clears {@code archived_at}. */
    record SupplierRequest(String name, String contact, String whatsapp, Integer leadDays,
            BigDecimal rating, boolean archived) {
    }

    /** One requested order line; a null unitCost falls back to the item's current cost. */
    record OrderLineRequest(UUID itemId, BigDecimal qty, BigDecimal unitCost) {
    }

    /** An order (placed or received) with its lines. */
    record Order(UUID id, UUID supplierId, String supplierName, String status,
            OffsetDateTime placedAt, OffsetDateTime receivedAt, UUID invoicePhotoId,
            BigDecimal total, List<ReceiveLine> lines) {
    }

    /** A purchase-order line as shown on the receive/history screens. */
    record ReceiveLine(UUID id, UUID itemId, String itemName, String uom,
            BigDecimal qtyOrdered, BigDecimal qtyReceived, BigDecimal unitCost,
            String lotNumber, BigDecimal deliveryCost) {
    }

    /** What the receiving form says was received against one order line. */
    record ReceiptLine(UUID lineId, BigDecimal qtyReceived, String lotNumber, BigDecimal deliveryCost) {
    }

    /** A (pending/decided) supplier-return header plus its lines. */
    record ReturnRequest(UUID id, UUID orderId, UUID supplierId, String supplierName, String status,
            OffsetDateTime requestedAt, List<ReturnLine> lines) {
    }

    /** One returned line for display on the return screens. */
    record ReturnLine(UUID orderLineId, String itemName, String uom, BigDecimal qty) {
    }

    /** A return line picked from the order's receivable lines. */
    record ReturnLineRequest(UUID orderLineId, BigDecimal qty) {
    }

    /** Items at/below their store alert, the seed for the ordering screen. */
    List<Shortage> shortages(UUID clinicId);

    /** Supplier directory; excludes archived unless {@code includeArchived}. */
    List<Supplier> suppliers(UUID clinicId, boolean includeArchived);

    /** create (null id) or update a supplier. */
    Supplier saveSupplier(UUID clinicId, Actor actor, UUID id, SupplierRequest request);

    /** Insert a placed order and its lines. */
    Order placeOrder(UUID clinicId, Actor actor, UUID supplierId, List<OrderLineRequest> lines);

    /** Arabic order summary for the wa.me handoff (controller builds the URL). */
    String whatsappMessage(UUID clinicId, UUID orderId);

    /** Orders restricted to the given statuses (pending-receipt vs history). */
    List<Order> orders(UUID clinicId, Set<PoStatus> statuses);

    /** Record receipt of lines against an order (invoice photo policy, A4). */
    Order receive(UUID clinicId, Actor actor, UUID orderId, List<ReceiptLine> lines, UUID invoicePhotoId);

    /** File a pending return (BR-G26: no stock movement yet; BR-G27 ceiling). */
    ReturnRequest requestReturn(UUID clinicId, Actor actor, UUID orderId, List<ReturnLineRequest> lines);

    /** Manager-only: approve writes the negative store movements; reject changes status only. */
    ReturnRequest decideReturn(UUID clinicId, Actor actor, UUID returnId, boolean approve);

    /** The pending supplier-return queue for the approvals screen. */
    List<ReturnRequest> pendingReturns(UUID clinicId);
}
