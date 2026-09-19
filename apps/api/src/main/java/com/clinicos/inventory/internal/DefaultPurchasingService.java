package com.clinicos.inventory.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.InventoryItem.INVENTORY_ITEM;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.PurchaseOrder.PURCHASE_ORDER;
import static com.clinicos.shared.jooq.tables.PurchaseOrderLine.PURCHASE_ORDER_LINE;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static com.clinicos.shared.jooq.tables.Supplier.SUPPLIER;
import static com.clinicos.shared.jooq.tables.SupplierReturn.SUPPLIER_RETURN;
import static com.clinicos.shared.jooq.tables.SupplierReturnLine.SUPPLIER_RETURN_LINE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.PurchasingService;
import com.clinicos.inventory.PurchasingService.Order;
import com.clinicos.inventory.PurchasingService.OrderLineRequest;
import com.clinicos.inventory.PurchasingService.ReceiptLine;
import com.clinicos.inventory.PurchasingService.ReceiveLine;
import com.clinicos.inventory.PurchasingService.ReturnLine;
import com.clinicos.inventory.PurchasingService.ReturnLineRequest;
import com.clinicos.inventory.PurchasingService.ReturnRequest;
import com.clinicos.inventory.PurchasingService.Shortage;
import com.clinicos.inventory.PurchasingService.Supplier;
import com.clinicos.inventory.PurchasingService.SupplierRequest;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.enums.MovementReason;
import com.clinicos.shared.jooq.enums.PoStatus;
import com.clinicos.shared.jooq.enums.ReturnStatus;

@Service
public class DefaultPurchasingService implements PurchasingService {

    private static final String RECEIPT_EXCEEDS_ARABIC = "الكمية المستلمة أكبر من المطلوبة";
    private static final String RETURN_EXCEEDS_ARABIC = "الكمية المرتجعة أكبر من المتاح للإرجاع من هذه الطلبية";

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultPurchasingService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Shortage> shortages(UUID clinicId) {
        return transactionTemplate.execute(status -> {
            var onStore = dsl.select(coalesce(sum(STOCK_MOVEMENT.QTY_DELTA), BigDecimal.ZERO))
                    .from(STOCK_MOVEMENT)
                    .where(STOCK_MOVEMENT.ITEM_ID.eq(INVENTORY_ITEM.ID))
                    .and(STOCK_MOVEMENT.LOCATION.eq(LocationKind.store))
                    .asField().coerce(BigDecimal.class);
            var lastCost = dsl.select(max(PURCHASE_ORDER_LINE.UNIT_COST))
                    .from(PURCHASE_ORDER_LINE)
                    .join(PURCHASE_ORDER).on(PURCHASE_ORDER_LINE.ORDER_ID.eq(PURCHASE_ORDER.ID))
                    .where(PURCHASE_ORDER_LINE.ITEM_ID.eq(INVENTORY_ITEM.ID))
                    .and(PURCHASE_ORDER.CLINIC_ID.eq(clinicId))
                    .asField();
            return dsl.select(INVENTORY_ITEM.ID, INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM,
                    INVENTORY_ITEM.STORE_ALERT,
                    INVENTORY_ITEM.PREFERRED_SUPPLIER_ID, SUPPLIER.NAME, lastCost, onStore)
                    .from(INVENTORY_ITEM)
                    .leftJoin(SUPPLIER).on(INVENTORY_ITEM.PREFERRED_SUPPLIER_ID.eq(SUPPLIER.ID))
                    .where(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                    .and(INVENTORY_ITEM.ARCHIVED_AT.isNull())
                    .orderBy(INVENTORY_ITEM.NAME.asc())
                    .fetch(r -> {
                        BigDecimal lastCostValue = r.get(lastCost, BigDecimal.class);
                        return new Shortage(
                                r.get(INVENTORY_ITEM.ID),
                                r.get(INVENTORY_ITEM.NAME),
                                r.get(INVENTORY_ITEM.UOM),
                                r.get(onStore, BigDecimal.class), r.get(INVENTORY_ITEM.STORE_ALERT),
                                r.get(INVENTORY_ITEM.PREFERRED_SUPPLIER_ID),
                                r.get(SUPPLIER.NAME),
                                lastCostValue,
                                lastCostValue == null);
                    })
                    .stream().filter(s -> s.storeAlert() != null && belowAlert(s.onHandStore(), s.storeAlert()))
                    .toList();
        });
    }

    @Override
    public List<Supplier> suppliers(UUID clinicId, boolean includeArchived) {
        return transactionTemplate.execute(status -> {
            var lastDelivery = dsl.select(max(PURCHASE_ORDER.RECEIVED_AT))
                    .from(PURCHASE_ORDER)
                    .where(PURCHASE_ORDER.SUPPLIER_ID.eq(SUPPLIER.ID))
                    .and(PURCHASE_ORDER.RECEIVED_AT.isNotNull())
                    .asField().coerce(OffsetDateTime.class);
            return dsl.select(SUPPLIER.ID, SUPPLIER.NAME, SUPPLIER.CONTACT, SUPPLIER.WHATSAPP,
                    SUPPLIER.LEAD_DAYS, SUPPLIER.RATING, SUPPLIER.ARCHIVED_AT, lastDelivery)
                    .from(SUPPLIER)
                    .where(SUPPLIER.CLINIC_ID.eq(clinicId))
                    .and(includeArchived ? SUPPLIER.CLINIC_ID.isNotNull() : SUPPLIER.ARCHIVED_AT.isNull())
                    .orderBy(SUPPLIER.NAME.asc())
                    .fetch(r -> new Supplier(
                            r.get(SUPPLIER.ID),
                            r.get(SUPPLIER.NAME),
                            r.get(SUPPLIER.CONTACT),
                            r.get(SUPPLIER.WHATSAPP),
                            r.get(SUPPLIER.LEAD_DAYS),
                            r.get(SUPPLIER.RATING),
                            r.get(lastDelivery, OffsetDateTime.class),
                            r.get(SUPPLIER.ARCHIVED_AT) != null));
        });
    }

    @Override
    public Supplier saveSupplier(UUID clinicId, Actor actor, UUID id, SupplierRequest request) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            if (request == null || isBlank(request.name())) {
                throw missing("اسم المورد مطلوب");
            }
            OffsetDateTime archivedAt = request.archived() ? OffsetDateTime.now() : null;
            UUID targetId = id != null ? id : UUID.randomUUID();
            if (id == null) {
                dsl.insertInto(SUPPLIER)
                        .set(SUPPLIER.ID, targetId)
                        .set(SUPPLIER.CLINIC_ID, clinicId)
                        .set(SUPPLIER.NAME, request.name().trim())
                        .set(SUPPLIER.CONTACT, blankToNull(request.contact()))
                        .set(SUPPLIER.WHATSAPP, blankToNull(request.whatsapp()))
                        .set(SUPPLIER.LEAD_DAYS, request.leadDays())
                        .set(SUPPLIER.RATING, request.rating())
                        .set(SUPPLIER.ARCHIVED_AT, archivedAt)
                        .execute();
            } else {
                var updated = dsl.update(SUPPLIER)
                        .set(SUPPLIER.NAME, request.name().trim())
                        .set(SUPPLIER.CONTACT, blankToNull(request.contact()))
                        .set(SUPPLIER.WHATSAPP, blankToNull(request.whatsapp()))
                        .set(SUPPLIER.LEAD_DAYS, request.leadDays())
                        .set(SUPPLIER.RATING, request.rating())
                        .set(SUPPLIER.ARCHIVED_AT, archivedAt)
                        .where(SUPPLIER.ID.eq(targetId))
                        .and(SUPPLIER.CLINIC_ID.eq(clinicId))
                        .execute();
                if (updated == 0) {
                    throw missing("المورد غير موجود");
                }
            }
            return suppliers(clinicId, true).stream()
                    .filter(s -> s.id().equals(targetId)).findFirst().orElseThrow();
        });
    }

    @Override
    public Order placeOrder(UUID clinicId, Actor actor, UUID supplierId, List<OrderLineRequest> lines) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            requireSupplier(clinicId, supplierId);
            if (lines == null || lines.isEmpty()) {
                throw missing("اختر صنفاً واحداً على الأقل للطلب");
            }
            var orderId = UUID.randomUUID();
            dsl.insertInto(PURCHASE_ORDER)
                    .set(PURCHASE_ORDER.ID, orderId)
                    .set(PURCHASE_ORDER.CLINIC_ID, clinicId)
                    .set(PURCHASE_ORDER.SUPPLIER_ID, supplierId)
                    .set(PURCHASE_ORDER.STATUS, PoStatus.placed)
                    .set(PURCHASE_ORDER.PLACED_BY, actor.membershipId())
                    .set(PURCHASE_ORDER.PLACED_AT, OffsetDateTime.now())
                    .execute();
            for (OrderLineRequest line : lines) {
                requireItem(clinicId, line.itemId());
                if (line.qty() == null || line.qty().signum() <= 0) {
                    throw missing("الكمية المطلوبة غير صالحة");
                }
                dsl.insertInto(PURCHASE_ORDER_LINE)
                        .set(PURCHASE_ORDER_LINE.ID, UUID.randomUUID())
                        .set(PURCHASE_ORDER_LINE.ORDER_ID, orderId)
                        .set(PURCHASE_ORDER_LINE.ITEM_ID, line.itemId())
                        .set(PURCHASE_ORDER_LINE.QTY_ORDERED, line.qty())
                        .set(PURCHASE_ORDER_LINE.UNIT_COST,
                                line.unitCost() != null ? line.unitCost()
                                        : requireItem(clinicId, line.itemId()).getUnitCost())
                        .execute();
            }
            return loadOrder(clinicId, orderId);
        });
    }

    @Override
    public String whatsappMessage(UUID clinicId, UUID orderId) {
        return transactionTemplate.execute(status -> {
            var order = loadOrder(clinicId, orderId);
            var sb = new StringBuilder("طلب مخزون جديد من ").append(order.supplierName()).append(":\n");
            for (ReceiveLine line : order.lines()) {
                sb.append("• ").append(line.itemName()).append(" × ").append(line.qtyOrdered())
                        .append(" ").append(line.uom()).append('\n');
            }
            sb.append("بالتوفيق.");
            return sb.toString();
        });
    }

    @Override
    public List<Order> orders(UUID clinicId, Set<PoStatus> statuses) {
        return transactionTemplate.execute(status -> {
            var base = dsl.select(PURCHASE_ORDER.ID).from(PURCHASE_ORDER)
                    .where(PURCHASE_ORDER.CLINIC_ID.eq(clinicId))
                    .and(PURCHASE_ORDER.STATUS.in(statuses))
                    .groupBy(PURCHASE_ORDER.ID)
                    .orderBy(DSL.max(PURCHASE_ORDER.PLACED_AT).desc())
                    .fetch(PURCHASE_ORDER.ID);
            return base.stream().map(id -> loadOrder(clinicId, id)).toList();
        });
    }

    @Override
    public Order receive(UUID clinicId, Actor actor, UUID orderId, List<ReceiptLine> lines, UUID invoicePhotoId) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            if (invoicePhotoId == null) {
                throw missing("صورة الفاتورة مطلوبة");
            }
            var order = requireOrder(clinicId, orderId);
            if (order.getStatus() != PoStatus.placed) {
                throw missing("الطلبية غير متاحة للاستلام");
            }
            try {
                for (ReceiptLine line : lines) {
                    if (line.qtyReceived() == null || line.qtyReceived().signum() < 0) {
                        throw missing("الكمية المستلمة غير صالحة");
                    }
                    if (line.deliveryCost() != null && line.deliveryCost().signum() < 0) {
                        throw missing("تكلفة التوصيل غير صالحة");
                    }
                    dsl.update(PURCHASE_ORDER_LINE)
                            .set(PURCHASE_ORDER_LINE.QTY_RECEIVED,
                                    PURCHASE_ORDER_LINE.QTY_RECEIVED.plus(line.qtyReceived()))
                            .where(PURCHASE_ORDER_LINE.ID.eq(line.lineId()))
                            .and(PURCHASE_ORDER_LINE.ORDER_ID.eq(orderId))
                            .execute();
                    if (line.lotNumber() != null) {
                        dsl.update(PURCHASE_ORDER_LINE)
                                .set(PURCHASE_ORDER_LINE.LOT_NUMBER, line.lotNumber())
                                .where(PURCHASE_ORDER_LINE.ID.eq(line.lineId()))
                                .and(PURCHASE_ORDER_LINE.ORDER_ID.eq(orderId))
                                .execute();
                    }
                    if (line.deliveryCost() != null && line.deliveryCost().signum() > 0) {
                        dsl.update(PURCHASE_ORDER_LINE)
                                .set(PURCHASE_ORDER_LINE.DELIVERY_COST, line.deliveryCost())
                                .where(PURCHASE_ORDER_LINE.ID.eq(line.lineId()))
                                .and(PURCHASE_ORDER_LINE.ORDER_ID.eq(orderId))
                                .execute();
                    }
                    var poLine = dsl.selectFrom(PURCHASE_ORDER_LINE)
                            .where(PURCHASE_ORDER_LINE.ID.eq(line.lineId()))
                            .and(PURCHASE_ORDER_LINE.ORDER_ID.eq(orderId)).fetchOne();
                    if (poLine == null) {
                        throw missing("سطر الطلبية غير موجود");
                    }
                    recordMovement(clinicId, actor, poLine.getItemId(), LocationKind.store,
                            line.qtyReceived(), MovementReason.receipt, "purchase_order", orderId);
                }
                dsl.update(PURCHASE_ORDER)
                        .set(PURCHASE_ORDER.STATUS, PoStatus.received)
                        .set(PURCHASE_ORDER.RECEIVED_AT, OffsetDateTime.now())
                        .set(PURCHASE_ORDER.INVOICE_PHOTO_ID, invoicePhotoId)
                        .where(PURCHASE_ORDER.ID.eq(orderId))
                        .execute();
                return loadOrder(clinicId, orderId);
            } catch (DataAccessException | org.jooq.exception.DataAccessException e) {
                if (hasMessage(e, "qty_received") || hasMessage(e, "CHECK") || hasMessage(e, "check constraint")) {
                    throw missing(RECEIPT_EXCEEDS_ARABIC);
                }
                throw e;
            }
        });
    }

    @Override
    public ReturnRequest requestReturn(UUID clinicId, Actor actor, UUID orderId, List<ReturnLineRequest> lines) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            if (lines == null || lines.isEmpty()) {
                throw missing("اختر صنفاً واحداً على الأقل للإرجاع");
            }
            var order = requireOrder(clinicId, orderId);
            var returnId = UUID.randomUUID();
            dsl.insertInto(SUPPLIER_RETURN)
                    .set(SUPPLIER_RETURN.ID, returnId)
                    .set(SUPPLIER_RETURN.CLINIC_ID, clinicId)
                    .set(SUPPLIER_RETURN.PURCHASE_ORDER_ID, orderId)
                    .set(SUPPLIER_RETURN.SUPPLIER_ID, order.getSupplierId())
                    .set(SUPPLIER_RETURN.REQUESTED_BY, actor.membershipId())
                    .set(SUPPLIER_RETURN.STATUS, ReturnStatus.pending)
                    .execute();
            try {
                for (ReturnLineRequest line : lines) {
                    if (line.qty() == null || line.qty().signum() <= 0) {
                        throw missing("الكمية المرتجعة غير صالحة");
                    }
                    dsl.insertInto(SUPPLIER_RETURN_LINE)
                            .set(SUPPLIER_RETURN_LINE.ID, UUID.randomUUID())
                            .set(SUPPLIER_RETURN_LINE.SUPPLIER_RETURN_ID, returnId)
                            .set(SUPPLIER_RETURN_LINE.PURCHASE_ORDER_LINE_ID, line.orderLineId())
                            .set(SUPPLIER_RETURN_LINE.QTY, line.qty())
                            .execute();
                }
            } catch (DataAccessException | org.jooq.exception.DataAccessException e) {
                if (hasMessage(e, "exceeds remaining receivable qty")) {
                    throw missing(RETURN_EXCEEDS_ARABIC);
                }
                throw e;
            }
            return loadReturn(clinicId, returnId);
        });
    }

    @Override
    public ReturnRequest decideReturn(UUID clinicId, Actor actor, UUID returnId, boolean approve) {
        return transactionTemplate.execute(status -> {
            requireManager(clinicId, actor);
            var row = dsl.selectFrom(SUPPLIER_RETURN)
                    .where(SUPPLIER_RETURN.ID.eq(returnId))
                    .and(SUPPLIER_RETURN.CLINIC_ID.eq(clinicId))
                    .fetchOne();
            if (row == null) {
                throw missing("طلب الإرجاع غير موجود");
            }
            if (row.getStatus() != ReturnStatus.pending) {
                throw missing("تمت معالجة الطلب من قبل");
            }
            if (approve) {
                for (var line : dsl.select(SUPPLIER_RETURN_LINE.PURCHASE_ORDER_LINE_ID,
                        SUPPLIER_RETURN_LINE.QTY).from(SUPPLIER_RETURN_LINE)
                        .where(SUPPLIER_RETURN_LINE.SUPPLIER_RETURN_ID.eq(returnId)).fetch()) {
                    var poLine = dsl.selectFrom(PURCHASE_ORDER_LINE)
                            .where(PURCHASE_ORDER_LINE.ID.eq(line.get(SUPPLIER_RETURN_LINE.PURCHASE_ORDER_LINE_ID)))
                            .fetchOne();
                    if (poLine == null) {
                        throw missing("سطر الطلبية غير موجود");
                    }
                    recordMovement(clinicId, actor, poLine.getItemId(), LocationKind.store,
                            line.get(SUPPLIER_RETURN_LINE.QTY).negate(), MovementReason.return_,
                            "supplier_return", returnId);
                }
            }
            dsl.update(SUPPLIER_RETURN)
                    .set(SUPPLIER_RETURN.STATUS, approve ? ReturnStatus.approved : ReturnStatus.rejected)
                    .set(SUPPLIER_RETURN.APPROVED_BY, actor.membershipId())
                    .set(SUPPLIER_RETURN.APPROVED_AT, OffsetDateTime.now())
                    .where(SUPPLIER_RETURN.ID.eq(returnId))
                    .execute();
            return loadReturn(clinicId, returnId);
        });
    }

    @Override
    public List<ReturnRequest> pendingReturns(UUID clinicId) {
        return transactionTemplate.execute(status -> dsl.select(SUPPLIER_RETURN.ID).from(SUPPLIER_RETURN)
                .where(SUPPLIER_RETURN.CLINIC_ID.eq(clinicId))
                .and(SUPPLIER_RETURN.STATUS.eq(ReturnStatus.pending))
                .orderBy(SUPPLIER_RETURN.REQUESTED_AT.desc())
                .fetch(SUPPLIER_RETURN.ID).stream()
                .map(id -> loadReturn(clinicId, id)).toList());
    }

    private Order loadOrder(UUID clinicId, UUID orderId) {
        var header = dsl.select(PURCHASE_ORDER.ID, PURCHASE_ORDER.SUPPLIER_ID, PURCHASE_ORDER.STATUS,
                PURCHASE_ORDER.PLACED_AT, PURCHASE_ORDER.RECEIVED_AT, PURCHASE_ORDER.INVOICE_PHOTO_ID,
                SUPPLIER.NAME)
                .from(PURCHASE_ORDER)
                .join(SUPPLIER).on(PURCHASE_ORDER.SUPPLIER_ID.eq(SUPPLIER.ID))
                .where(PURCHASE_ORDER.ID.eq(orderId))
                .and(PURCHASE_ORDER.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (header == null) {
            throw missing("الطلبية غير موجودة");
        }
        var lines = dsl.select(PURCHASE_ORDER_LINE.ID, PURCHASE_ORDER_LINE.ITEM_ID,
                PURCHASE_ORDER_LINE.QTY_ORDERED, PURCHASE_ORDER_LINE.QTY_RECEIVED,
                PURCHASE_ORDER_LINE.UNIT_COST, PURCHASE_ORDER_LINE.LOT_NUMBER,
                PURCHASE_ORDER_LINE.DELIVERY_COST, INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM)
                .from(PURCHASE_ORDER_LINE)
                .join(INVENTORY_ITEM).on(PURCHASE_ORDER_LINE.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .where(PURCHASE_ORDER_LINE.ORDER_ID.eq(orderId))
                .orderBy(INVENTORY_ITEM.NAME.asc())
                .fetch(r -> new ReceiveLine(
                        r.get(PURCHASE_ORDER_LINE.ID),
                        r.get(PURCHASE_ORDER_LINE.ITEM_ID),
                        r.get(INVENTORY_ITEM.NAME),
                        r.get(INVENTORY_ITEM.UOM),
                        r.get(PURCHASE_ORDER_LINE.QTY_ORDERED),
                        r.get(PURCHASE_ORDER_LINE.QTY_RECEIVED),
                        r.get(PURCHASE_ORDER_LINE.UNIT_COST),
                        r.get(PURCHASE_ORDER_LINE.LOT_NUMBER),
                        r.get(PURCHASE_ORDER_LINE.DELIVERY_COST)));
        var total = lines.stream().map(l -> l.unitCost().multiply(l.qtyOrdered())
                .add(l.deliveryCost() != null ? l.deliveryCost() : BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Order(
                header.get(PURCHASE_ORDER.ID),
                header.get(PURCHASE_ORDER.SUPPLIER_ID),
                header.get(SUPPLIER.NAME),
                header.get(PURCHASE_ORDER.STATUS).getLiteral(),
                header.get(PURCHASE_ORDER.PLACED_AT),
                header.get(PURCHASE_ORDER.RECEIVED_AT),
                header.get(PURCHASE_ORDER.INVOICE_PHOTO_ID),
                total, lines);
    }

    private ReturnRequest loadReturn(UUID clinicId, UUID returnId) {
        var header = dsl.select(SUPPLIER_RETURN.ID, SUPPLIER_RETURN.PURCHASE_ORDER_ID,
                SUPPLIER_RETURN.SUPPLIER_ID, SUPPLIER_RETURN.STATUS, SUPPLIER_RETURN.REQUESTED_AT, SUPPLIER.NAME)
                .from(SUPPLIER_RETURN)
                .join(SUPPLIER).on(SUPPLIER_RETURN.SUPPLIER_ID.eq(SUPPLIER.ID))
                .where(SUPPLIER_RETURN.ID.eq(returnId))
                .and(SUPPLIER_RETURN.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (header == null) {
            throw missing("طلب الإرجاع غير موجود");
        }
        var lines = dsl.select(SUPPLIER_RETURN_LINE.PURCHASE_ORDER_LINE_ID, SUPPLIER_RETURN_LINE.QTY,
                INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM)
                .from(SUPPLIER_RETURN_LINE)
                .join(PURCHASE_ORDER_LINE).on(SUPPLIER_RETURN_LINE.PURCHASE_ORDER_LINE_ID.eq(PURCHASE_ORDER_LINE.ID))
                .join(INVENTORY_ITEM).on(PURCHASE_ORDER_LINE.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .where(SUPPLIER_RETURN_LINE.SUPPLIER_RETURN_ID.eq(returnId))
                .fetch(r -> new ReturnLine(
                        r.get(SUPPLIER_RETURN_LINE.PURCHASE_ORDER_LINE_ID),
                        r.get(INVENTORY_ITEM.NAME),
                        r.get(INVENTORY_ITEM.UOM),
                        r.get(SUPPLIER_RETURN_LINE.QTY)));
        return new ReturnRequest(
                header.get(SUPPLIER_RETURN.ID),
                header.get(SUPPLIER_RETURN.PURCHASE_ORDER_ID),
                header.get(SUPPLIER_RETURN.SUPPLIER_ID),
                header.get(SUPPLIER.NAME),
                header.get(SUPPLIER_RETURN.STATUS).getLiteral(),
                header.get(SUPPLIER_RETURN.REQUESTED_AT),
                lines);
    }

    private com.clinicos.shared.jooq.tables.records.PurchaseOrderRecord requireOrder(UUID clinicId, UUID orderId) {
        var row = dsl.selectFrom(PURCHASE_ORDER)
                .where(PURCHASE_ORDER.ID.eq(orderId))
                .and(PURCHASE_ORDER.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (row == null) {
            throw missing("الطلبية غير موجودة");
        }
        return row;
    }

    private void requireSupplier(UUID clinicId, UUID supplierId) {
        if (supplierId == null
                || !dsl.fetchExists(dsl.selectOne().from(SUPPLIER)
                        .where(SUPPLIER.ID.eq(supplierId))
                        .and(SUPPLIER.CLINIC_ID.eq(clinicId)))) {
            throw missing("المورد غير موجود");
        }
    }

    private com.clinicos.shared.jooq.tables.records.InventoryItemRecord requireItem(UUID clinicId, UUID itemId) {
        var item = dsl.selectFrom(INVENTORY_ITEM)
                .where(INVENTORY_ITEM.ID.eq(itemId))
                .and(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (item == null) {
            throw missing("الصنف غير موجود");
        }
        return item;
    }

    private void recordMovement(UUID clinicId, Actor actor, UUID itemId, LocationKind location,
            BigDecimal qtyDelta, MovementReason reason, String refType, UUID refId) {
        dsl.insertInto(STOCK_MOVEMENT)
                .set(STOCK_MOVEMENT.ID, UUID.randomUUID())
                .set(STOCK_MOVEMENT.CLINIC_ID, clinicId)
                .set(STOCK_MOVEMENT.ITEM_ID, itemId)
                .set(STOCK_MOVEMENT.LOCATION, location)
                .set(STOCK_MOVEMENT.QTY_DELTA, qtyDelta)
                .set(STOCK_MOVEMENT.REASON, reason)
                .set(STOCK_MOVEMENT.REF_TYPE, refType)
                .set(STOCK_MOVEMENT.REF_ID, refId)
                .set(STOCK_MOVEMENT.CREATED_BY, actor.membershipId())
                .execute();
    }

    private void requireActiveActor(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null) {
            throw missing("غير مصرح");
        }
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP)
                .where(MEMBERSHIP.ID.eq(actor.membershipId()))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active)))) {
            throw missing("غير مصرح");
        }
    }

    private void requireManager(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null) {
            throw missing("غير مصرح");
        }
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP).join(ROLE).on(MEMBERSHIP.ROLE_ID.eq(ROLE.ID))
                .where(MEMBERSHIP.ID.eq(actor.membershipId()))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .and(ROLE.CODE.in("owner", "manager")))) {
            throw missing("غير مصرح — تتطلب صلاحيات مدير");
        }
    }

    private boolean belowAlert(BigDecimal onHand, Integer alert) {
        return alert != null && onHand != null && onHand.compareTo(BigDecimal.valueOf(alert)) < 0;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private boolean hasMessage(Throwable error, String text) {
        for (var current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) return true;
        }
        return false;
    }

    private IllegalArgumentException missing(String message) {
        return new IllegalArgumentException(message);
    }
}
