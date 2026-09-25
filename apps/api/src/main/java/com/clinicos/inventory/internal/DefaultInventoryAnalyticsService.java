package com.clinicos.inventory.internal;

import static com.clinicos.shared.jooq.tables.InventoryChangeRequest.INVENTORY_CHANGE_REQUEST;
import static com.clinicos.shared.jooq.tables.InventoryItem.INVENTORY_ITEM;
import static com.clinicos.shared.jooq.tables.Procedure.PROCEDURE;
import static com.clinicos.shared.jooq.tables.ProcedureCase.PROCEDURE_CASE;
import static com.clinicos.shared.jooq.tables.ProcedureCaseItem.PROCEDURE_CASE_ITEM;
import static com.clinicos.shared.jooq.tables.PurchaseOrder.PURCHASE_ORDER;
import static com.clinicos.shared.jooq.tables.PurchaseOrderLine.PURCHASE_ORDER_LINE;
import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static com.clinicos.shared.jooq.tables.Supplier.SUPPLIER;
import static com.clinicos.shared.jooq.tables.SupplierReturn.SUPPLIER_RETURN;
import static com.clinicos.shared.jooq.tables.SupplierReturnLine.SUPPLIER_RETURN_LINE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.jooq.DSLContext;

import com.clinicos.inventory.InventoryAnalyticsService;
import com.clinicos.shared.jooq.enums.ChangeRequestStatus;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MovementReason;

@Service
public class DefaultInventoryAnalyticsService implements InventoryAnalyticsService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultInventoryAnalyticsService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Dashboard dashboard(UUID clinicId) {
        return transactionTemplate.execute(status -> {
            var items = dsl.select(INVENTORY_ITEM.ID, INVENTORY_ITEM.STORE_ALERT, INVENTORY_ITEM.TRAY_ALERT,
                    INVENTORY_ITEM.UNIT_COST).from(INVENTORY_ITEM).where(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                    .and(INVENTORY_ITEM.ARCHIVED_AT.isNull()).fetch();
            var balances = balances(clinicId);
            int below = 0;
            BigDecimal store = BigDecimal.ZERO;
            BigDecimal tray = BigDecimal.ZERO;
            for (var item : items) {
                var quantities = balances.getOrDefault(item.get(INVENTORY_ITEM.ID), Map.of());
                var storeQty = quantities.getOrDefault(LocationKind.store, BigDecimal.ZERO);
                var trayQty = quantities.getOrDefault(LocationKind.tray, BigDecimal.ZERO);
                if ((item.get(INVENTORY_ITEM.STORE_ALERT) != null && storeQty.compareTo(BigDecimal.valueOf(item.get(INVENTORY_ITEM.STORE_ALERT))) < 0)
                        || (item.get(INVENTORY_ITEM.TRAY_ALERT) != null && trayQty.compareTo(BigDecimal.valueOf(item.get(INVENTORY_ITEM.TRAY_ALERT))) < 0)) below++;
                store = store.add(storeQty.multiply(item.get(INVENTORY_ITEM.UNIT_COST)));
                tray = tray.add(trayQty.multiply(item.get(INVENTORY_ITEM.UNIT_COST)));
            }
            int approvals = dsl.fetchCount(INVENTORY_CHANGE_REQUEST, INVENTORY_CHANGE_REQUEST.CLINIC_ID.eq(clinicId)
                    .and(INVENTORY_CHANGE_REQUEST.STATUS.eq(ChangeRequestStatus.pending)))
                    + dsl.fetchCount(SUPPLIER_RETURN, SUPPLIER_RETURN.CLINIC_ID.eq(clinicId)
                            .and(SUPPLIER_RETURN.STATUS.eq(com.clinicos.shared.jooq.enums.ReturnStatus.pending)));
            int orders = dsl.fetchCount(PURCHASE_ORDER, PURCHASE_ORDER.CLINIC_ID.eq(clinicId)
                    .and(PURCHASE_ORDER.STATUS.eq(com.clinicos.shared.jooq.enums.PoStatus.placed)));
            return new Dashboard(below, approvals, orders, tray, store);
        });
    }

    @Override
    public List<ProfitRow> profit(UUID clinicId, LocalDate from, LocalDate to) {
        return transactionTemplate.execute(status -> {
            var rows = caseRows(clinicId, from, to);
            var result = new HashMap<String, BigDecimal[]>();
            for (var row : rows) {
                var values = result.computeIfAbsent(row.procedureName, key -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
                values[0] = values[0].add(row.price);
                values[1] = values[1].add(row.materialCost);
                values[2] = values[2].add(row.laborCost);
                values[3] = values[3].add(row.doctorFee);
                values[4] = values[4].add(BigDecimal.ONE);
            }
            return result.entrySet().stream().map(entry -> { var v = entry.getValue();
                return new ProfitRow(entry.getKey(), v[0], v[1], v[2], v[3], v[0].subtract(v[1]).subtract(v[2]).subtract(v[3]), v[4].longValue()); }).toList();
        });
    }

    @Override
    public List<ConsumptionRow> consumption(UUID clinicId, LocalDate from, LocalDate to) {
        return transactionTemplate.execute(status -> {
            var consumed = dsl.select(STOCK_MOVEMENT.ITEM_ID, STOCK_MOVEMENT.QTY_DELTA, INVENTORY_ITEM.NAME,
                    INVENTORY_ITEM.UNIT_COST).from(STOCK_MOVEMENT).join(INVENTORY_ITEM).on(STOCK_MOVEMENT.ITEM_ID.eq(INVENTORY_ITEM.ID))
                    .where(STOCK_MOVEMENT.CLINIC_ID.eq(clinicId)).and(STOCK_MOVEMENT.REASON.eq(MovementReason.issue))
                    .and(range(STOCK_MOVEMENT.CREATED_AT, from, to)).fetch();
            var onHand = balances(clinicId);
            var grouped = new HashMap<UUID, BigDecimal>();
            consumed.forEach(row -> grouped.merge(row.get(STOCK_MOVEMENT.ITEM_ID), row.get(STOCK_MOVEMENT.QTY_DELTA).abs(), BigDecimal::add));
            return consumed.stream().map(row -> row.get(STOCK_MOVEMENT.ITEM_ID)).distinct().map(id -> {
                var row = consumed.stream().filter(it -> it.get(STOCK_MOVEMENT.ITEM_ID).equals(id)).findFirst().orElseThrow();
                var balance = onHand.getOrDefault(id, Map.of()).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                var value = balance.multiply(row.get(INVENTORY_ITEM.UNIT_COST));
                var quantity = grouped.get(id);
                return new ConsumptionRow(row.get(INVENTORY_ITEM.NAME), quantity, balance, value,
                        value.signum() == 0 ? BigDecimal.ZERO : quantity.multiply(row.get(INVENTORY_ITEM.UNIT_COST)).divide(value, 2, java.math.RoundingMode.HALF_UP));
            }).toList();
        });
    }

    @Override
    public List<WasteRow> waste(UUID clinicId, LocalDate from, LocalDate to) {
        return transactionTemplate.execute(status -> dsl.select(STOCK_MOVEMENT.CREATED_AT, STOCK_MOVEMENT.QTY_DELTA,
                INVENTORY_ITEM.NAME, INVENTORY_ITEM.UNIT_COST).from(STOCK_MOVEMENT).join(INVENTORY_ITEM)
                .on(STOCK_MOVEMENT.ITEM_ID.eq(INVENTORY_ITEM.ID)).where(STOCK_MOVEMENT.CLINIC_ID.eq(clinicId))
                .and(STOCK_MOVEMENT.REASON.in(MovementReason.adjustment, MovementReason.count))
                .and(STOCK_MOVEMENT.QTY_DELTA.lt(BigDecimal.ZERO)).and(range(STOCK_MOVEMENT.CREATED_AT, from, to))
                .orderBy(STOCK_MOVEMENT.CREATED_AT.desc()).fetch(row -> new WasteRow(
                        row.get(STOCK_MOVEMENT.CREATED_AT).toLocalDate().withDayOfMonth(1).toString(), row.get(INVENTORY_ITEM.NAME),
                        row.get(STOCK_MOVEMENT.QTY_DELTA).abs(), row.get(STOCK_MOVEMENT.QTY_DELTA).abs().multiply(row.get(INVENTORY_ITEM.UNIT_COST)))));
    }

    @Override
    public List<DoctorRow> doctors(UUID clinicId, LocalDate from, LocalDate to) {
        return transactionTemplate.execute(status -> {
            var rows = caseRows(clinicId, from, to);
            var grouped = new HashMap<String, BigDecimal[]>();
            for (var row : rows) {
                var key = row.doctorName == null || row.doctorName.isBlank() ? "غير محدد" : row.doctorName;
                var values = grouped.computeIfAbsent(key, unused -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
                values[0] = values[0].add(BigDecimal.ONE); values[1] = values[1].add(row.price); values[2] = values[2].add(row.materialCost); values[3] = values[3].add(row.laborCost); values[4] = values[4].add(row.doctorFee);
            }
            return grouped.entrySet().stream().map(it -> { var v = it.getValue();
                return new DoctorRow(it.getKey(), v[0].longValue(), v[1], v[2], v[3], v[4], v[1].subtract(v[2]).subtract(v[3]).subtract(v[4])); }).toList();
        });
    }

    @Override
    public List<SupplierRow> suppliers(UUID clinicId, LocalDate from, LocalDate to) {
        return transactionTemplate.execute(status -> {
            var rows = dsl.select(PURCHASE_ORDER.ID, PURCHASE_ORDER.PLACED_AT, PURCHASE_ORDER.RECEIVED_AT,
                    PURCHASE_ORDER_LINE.UNIT_COST, PURCHASE_ORDER_LINE.QTY_ORDERED, SUPPLIER.NAME)
                    .from(PURCHASE_ORDER).join(PURCHASE_ORDER_LINE).on(PURCHASE_ORDER.ID.eq(PURCHASE_ORDER_LINE.ORDER_ID))
                    .join(SUPPLIER).on(PURCHASE_ORDER.SUPPLIER_ID.eq(SUPPLIER.ID)).where(PURCHASE_ORDER.CLINIC_ID.eq(clinicId))
                    .and(range(PURCHASE_ORDER.PLACED_AT, from, to)).fetch();
            var grouped = new LinkedHashMap<String, BigDecimal[]>();
            rows.forEach(row -> { var values = grouped.computeIfAbsent(row.get(SUPPLIER.NAME), unused -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO }); values[0] = values[0].add(BigDecimal.ONE); values[1] = values[1].add(row.get(PURCHASE_ORDER_LINE.UNIT_COST).multiply(row.get(PURCHASE_ORDER_LINE.QTY_ORDERED))); values[2] = values[2].add(row.get(PURCHASE_ORDER_LINE.UNIT_COST)); if (row.get(PURCHASE_ORDER.PLACED_AT) != null && row.get(PURCHASE_ORDER.RECEIVED_AT) != null) values[3] = values[3].add(BigDecimal.valueOf(java.time.Duration.between(row.get(PURCHASE_ORDER.PLACED_AT), row.get(PURCHASE_ORDER.RECEIVED_AT)).toDays())); values[4] = values[4].add(row.get(PURCHASE_ORDER_LINE.QTY_ORDERED)); });
            var returned = new HashMap<String, BigDecimal>();
            dsl.select(SUPPLIER.NAME, SUPPLIER_RETURN_LINE.QTY).from(SUPPLIER_RETURN_LINE)
                    .join(SUPPLIER_RETURN).on(SUPPLIER_RETURN_LINE.SUPPLIER_RETURN_ID.eq(SUPPLIER_RETURN.ID))
                    .join(PURCHASE_ORDER).on(SUPPLIER_RETURN.PURCHASE_ORDER_ID.eq(PURCHASE_ORDER.ID))
                    .join(SUPPLIER).on(SUPPLIER_RETURN.SUPPLIER_ID.eq(SUPPLIER.ID))
                    .where(SUPPLIER_RETURN.CLINIC_ID.eq(clinicId)).and(range(PURCHASE_ORDER.PLACED_AT, from, to))
                    .fetch().forEach(row -> returned.merge(row.get(SUPPLIER.NAME), row.get(SUPPLIER_RETURN_LINE.QTY), BigDecimal::add));
            return grouped.entrySet().stream().map(it -> { var v = it.getValue(); var count = v[0].longValue();
                var rate = v[4].signum() == 0 ? BigDecimal.ZERO
                        : returned.getOrDefault(it.getKey(), BigDecimal.ZERO).divide(v[4], 4, java.math.RoundingMode.HALF_UP);
                return new SupplierRow(it.getKey(), count, v[1], v[2].divide(v[0], 2, java.math.RoundingMode.HALF_UP), v[3].divide(v[0], 2, java.math.RoundingMode.HALF_UP), rate); }).toList();
        });
    }

    @Override
    public List<ItemPriceRow> itemPrices(UUID clinicId, LocalDate from, LocalDate to) {
        return transactionTemplate.execute(status -> dsl.select(INVENTORY_ITEM.NAME, SUPPLIER.NAME,
                PURCHASE_ORDER.PLACED_AT, PURCHASE_ORDER_LINE.UNIT_COST).from(PURCHASE_ORDER_LINE)
                .join(PURCHASE_ORDER).on(PURCHASE_ORDER_LINE.ORDER_ID.eq(PURCHASE_ORDER.ID)).join(INVENTORY_ITEM)
                .on(PURCHASE_ORDER_LINE.ITEM_ID.eq(INVENTORY_ITEM.ID)).join(SUPPLIER)
                .on(PURCHASE_ORDER.SUPPLIER_ID.eq(SUPPLIER.ID)).where(PURCHASE_ORDER.CLINIC_ID.eq(clinicId))
                .and(range(PURCHASE_ORDER.PLACED_AT, from, to)).orderBy(PURCHASE_ORDER.PLACED_AT.desc())
                .fetch(row -> new ItemPriceRow(row.get(INVENTORY_ITEM.NAME), row.get(SUPPLIER.NAME),
                        row.get(PURCHASE_ORDER.PLACED_AT), row.get(PURCHASE_ORDER_LINE.UNIT_COST))));
    }

    private Map<UUID, Map<LocationKind, BigDecimal>> balances(UUID clinicId) {
        var result = new HashMap<UUID, Map<LocationKind, BigDecimal>>();
        dsl.select(STOCK_MOVEMENT.ITEM_ID, STOCK_MOVEMENT.LOCATION, org.jooq.impl.DSL.sum(STOCK_MOVEMENT.QTY_DELTA))
                .from(STOCK_MOVEMENT).where(STOCK_MOVEMENT.CLINIC_ID.eq(clinicId)).groupBy(STOCK_MOVEMENT.ITEM_ID, STOCK_MOVEMENT.LOCATION)
                .fetch().forEach(row -> result.computeIfAbsent(row.get(STOCK_MOVEMENT.ITEM_ID), unused -> new HashMap<>())
                        .put(row.get(STOCK_MOVEMENT.LOCATION), row.get(org.jooq.impl.DSL.sum(STOCK_MOVEMENT.QTY_DELTA))));
        return result;
    }

    private org.jooq.Condition range(org.jooq.Field<OffsetDateTime> field, LocalDate from, LocalDate to) {
        org.jooq.Condition condition = org.jooq.impl.DSL.trueCondition();
        if (from != null) condition = condition.and(field.ge(from.atStartOfDay().atOffset(ZoneOffset.UTC)));
        if (to != null) condition = condition.and(field.lt(to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
        return condition;
    }

    private List<CaseRow> caseRows(UUID clinicId, LocalDate from, LocalDate to) {
        var rows = dsl.select(PROCEDURE_CASE.ID, PROCEDURE_CASE.DOCTOR_NAME, PROCEDURE.NAME, PROCEDURE.PRICE,
                PROCEDURE.LABOR_COST, PROCEDURE.DOCTOR_FEE, PROCEDURE_CASE_ITEM.QTY, PROCEDURE_CASE_ITEM.UNIT_COST_AT_TIME)
                .from(PROCEDURE_CASE).join(PROCEDURE).on(PROCEDURE_CASE.PROCEDURE_ID.eq(PROCEDURE.ID))
                .join(PROCEDURE_CASE_ITEM).on(PROCEDURE_CASE.ID.eq(PROCEDURE_CASE_ITEM.PROCEDURE_CASE_ID))
                .where(PROCEDURE_CASE.CLINIC_ID.eq(clinicId)).and(range(PROCEDURE_CASE.PERFORMED_AT, from, to)).fetch();
        var grouped = new LinkedHashMap<UUID, CaseRow>();
        rows.forEach(row -> grouped.compute(row.get(PROCEDURE_CASE.ID), (id, old) -> {
            var material = row.get(PROCEDURE_CASE_ITEM.QTY).multiply(row.get(PROCEDURE_CASE_ITEM.UNIT_COST_AT_TIME))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            var materialCost = old == null ? material : old.materialCost.add(material);
            return old == null ? new CaseRow(row.get(PROCEDURE.NAME), row.get(PROCEDURE_CASE.DOCTOR_NAME), row.get(PROCEDURE.PRICE), materialCost,
                    row.get(PROCEDURE.LABOR_COST), row.get(PROCEDURE.DOCTOR_FEE))
                    : new CaseRow(old.procedureName, old.doctorName, old.price, materialCost, old.laborCost, old.doctorFee);
        }));
        return new ArrayList<>(grouped.values());
    }

    private record CaseRow(String procedureName, String doctorName, BigDecimal price, BigDecimal materialCost,
            BigDecimal laborCost, BigDecimal doctorFee) { }
}
