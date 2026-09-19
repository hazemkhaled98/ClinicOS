package com.clinicos.inventory.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.InventoryChangeRequest.INVENTORY_CHANGE_REQUEST;
import static com.clinicos.shared.jooq.tables.InventoryItem.INVENTORY_ITEM;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.sum;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.TableField;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.InventoryService.ChangeRequest;
import com.clinicos.inventory.InventoryService.Item;
import com.clinicos.inventory.InventoryService.ItemRequest;
import com.clinicos.inventory.InventoryService.IssueResult;
import com.clinicos.inventory.InventoryService.MovementEntry;
import com.clinicos.inventory.InventoryService.StockLine;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.shared.jooq.enums.ChangeRequestStatus;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.enums.MovementReason;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DefaultInventoryService implements InventoryService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultInventoryService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Item> items(UUID clinicId, boolean includeArchived) {
        return transactionTemplate.execute(status -> {
            var onStore = onHandAt(LocationKind.store);
            var onTray = onHandAt(LocationKind.tray);
            return dsl.select(
                    INVENTORY_ITEM.ID, INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM,
                    INVENTORY_ITEM.UNITS_PER_PACK, INVENTORY_ITEM.UNIT_COST,
                    INVENTORY_ITEM.STORE_ALERT, INVENTORY_ITEM.TRAY_ALERT,
                    INVENTORY_ITEM.ARCHIVED_AT, onStore, onTray)
                    .from(INVENTORY_ITEM)
                    .where(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                    .and(includeArchived
                            ? INVENTORY_ITEM.CLINIC_ID.isNotNull()
                            : INVENTORY_ITEM.ARCHIVED_AT.isNull())
                    .orderBy(INVENTORY_ITEM.NAME.asc())
                    .fetch(r -> {
                        var store = r.get(onStore);
                        var tray = r.get(onTray);
                        return new Item(
                                r.get(INVENTORY_ITEM.ID),
                                r.get(INVENTORY_ITEM.NAME),
                                r.get(INVENTORY_ITEM.UOM),
                                r.get(INVENTORY_ITEM.UNITS_PER_PACK),
                                r.get(INVENTORY_ITEM.UNIT_COST),
                                r.get(INVENTORY_ITEM.STORE_ALERT),
                                r.get(INVENTORY_ITEM.TRAY_ALERT),
                                store, tray,
                                belowAlert(store, r.get(INVENTORY_ITEM.STORE_ALERT)),
                                belowAlert(tray, r.get(INVENTORY_ITEM.TRAY_ALERT)),
                                r.get(INVENTORY_ITEM.ARCHIVED_AT));
                    });
        });
    }

    @Override
    public List<StockLine> stock(UUID clinicId, LocationKind location) {
        return transactionTemplate.execute(status -> {
            var onHand = onHandAt(location);
            return dsl.select(
                    INVENTORY_ITEM.ID, INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM,
                    INVENTORY_ITEM.UNIT_COST, INVENTORY_ITEM.STORE_ALERT, INVENTORY_ITEM.TRAY_ALERT, onHand)
                    .from(INVENTORY_ITEM)
                    .where(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                    .and(INVENTORY_ITEM.ARCHIVED_AT.isNull())
                    .orderBy(INVENTORY_ITEM.NAME.asc())
                    .fetch(r -> new StockLine(
                            r.get(INVENTORY_ITEM.ID),
                            r.get(INVENTORY_ITEM.NAME),
                            r.get(INVENTORY_ITEM.UOM),
                            r.get(INVENTORY_ITEM.UNIT_COST),
                            r.get(onHand),
                            alertOf(r, location),
                            belowAlert(r.get(onHand), alertOf(r, location))));
        });
    }

    @Override
    public Item createItem(UUID clinicId, Actor actor, ItemRequest request) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            if (request == null || isBlank(request.name()) || isBlank(request.uom()) || request.unitCost() == null) {
                throw missing("الاسم ووحدة القياس والتكلفة مطلوبة");
            }
            var id = UUID.randomUUID();
            dsl.insertInto(INVENTORY_ITEM)
                    .set(INVENTORY_ITEM.ID, id)
                    .set(INVENTORY_ITEM.CLINIC_ID, clinicId)
                    .set(INVENTORY_ITEM.NAME, request.name().trim())
                    .set(INVENTORY_ITEM.UOM, request.uom().trim())
                    .set(INVENTORY_ITEM.UNITS_PER_PACK, request.unitsPerPack())
                    .set(INVENTORY_ITEM.UNIT_COST, request.unitCost())
                    .set(INVENTORY_ITEM.STORE_ALERT, request.storeAlert())
                    .set(INVENTORY_ITEM.TRAY_ALERT, request.trayAlert())
                    .execute();
            return new Item(id, request.name(), request.uom(), request.unitsPerPack(), request.unitCost(),
                    request.storeAlert(), request.trayAlert(), BigDecimal.ZERO, BigDecimal.ZERO,
                    false, false, null);
        });
    }

    @Override
    public ChangeRequest requestItemChange(UUID clinicId, Actor actor, UUID itemId,
            ChangeRequestKind kind, ItemRequest proposedValues) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            requireItem(clinicId, itemId);
            if (kind == null) throw missing("نوع الطلب غير محدد");
            JSONB payload = null;
            if (kind == ChangeRequestKind.edit) {
                if (proposedValues == null) throw missing("البيانات المقترحة للتعديل مطلوبة");
                payload = serialize(proposedValues);
            }
            var id = UUID.randomUUID();
            dsl.insertInto(INVENTORY_CHANGE_REQUEST)
                    .set(INVENTORY_CHANGE_REQUEST.ID, id)
                    .set(INVENTORY_CHANGE_REQUEST.CLINIC_ID, clinicId)
                    .set(INVENTORY_CHANGE_REQUEST.KIND, kind)
                    .set(INVENTORY_CHANGE_REQUEST.ITEM_ID, itemId)
                    .set(INVENTORY_CHANGE_REQUEST.PAYLOAD, payload)
                    .set(INVENTORY_CHANGE_REQUEST.REQUESTED_BY, actor.membershipId())
                    .set(INVENTORY_CHANGE_REQUEST.STATUS, ChangeRequestStatus.pending)
                    .execute();
            return requireChangeRequest(clinicId, id);
        });
    }

    @Override
    public List<ChangeRequest> pendingChangeRequests(UUID clinicId) {
        return transactionTemplate.execute(status -> dsl.select(
                INVENTORY_CHANGE_REQUEST.ID, INVENTORY_CHANGE_REQUEST.KIND,
                INVENTORY_CHANGE_REQUEST.ITEM_ID, INVENTORY_CHANGE_REQUEST.PAYLOAD,
                INVENTORY_CHANGE_REQUEST.REQUESTED_AT, INVENTORY_CHANGE_REQUEST.STATUS,
                INVENTORY_ITEM.NAME)
                .from(INVENTORY_CHANGE_REQUEST)
                .join(INVENTORY_ITEM).on(INVENTORY_CHANGE_REQUEST.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .where(INVENTORY_CHANGE_REQUEST.CLINIC_ID.eq(clinicId))
                .and(INVENTORY_CHANGE_REQUEST.STATUS.eq(ChangeRequestStatus.pending))
                .orderBy(INVENTORY_CHANGE_REQUEST.REQUESTED_AT.desc())
                .fetch(r -> toChangeRequest(r)));
    }

    @Override
    public ChangeRequest applyItemChange(UUID clinicId, Actor actor, UUID requestId, boolean approve) {
        return transactionTemplate.execute(status -> {
            requireManager(clinicId, actor);
            var row = dsl.selectFrom(INVENTORY_CHANGE_REQUEST)
                    .where(INVENTORY_CHANGE_REQUEST.ID.eq(requestId))
                    .and(INVENTORY_CHANGE_REQUEST.CLINIC_ID.eq(clinicId))
                    .fetchOne();
            if (row == null) throw missing("طلب التعديل غير موجود");
            if (row.getStatus() != ChangeRequestStatus.pending) throw missing("تمت معالجة الطلب من قبل");
            if (approve) {
                if (row.getKind() == ChangeRequestKind.delete) {
                    requireItem(clinicId, row.getItemId());
                    dsl.update(INVENTORY_ITEM)
                            .set(INVENTORY_ITEM.ARCHIVED_AT, OffsetDateTime.now())
                            .where(INVENTORY_ITEM.ID.eq(row.getItemId()))
                            .and(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                            .execute();
                } else {
                    applyEditPayload(clinicId, row.getItemId(), row.getPayload());
                }
            }
            dsl.update(INVENTORY_CHANGE_REQUEST)
                    .set(INVENTORY_CHANGE_REQUEST.STATUS, approve ? ChangeRequestStatus.approved : ChangeRequestStatus.rejected)
                    .set(INVENTORY_CHANGE_REQUEST.DECIDED_BY, actor.membershipId())
                    .set(INVENTORY_CHANGE_REQUEST.DECIDED_AT, OffsetDateTime.now())
                    .where(INVENTORY_CHANGE_REQUEST.ID.eq(requestId)).execute();
            return requireChangeRequest(clinicId, requestId);
        });
    }

    @Override
    public IssueResult issue(UUID clinicId, Actor actor, UUID itemId, LocationKind location,
            BigDecimal requestedQty) {
        return transactionTemplate.execute(status -> {
            requireActiveActor(clinicId, actor);
            requireItem(clinicId, itemId);
            if (location == null || requestedQty == null || requestedQty.signum() <= 0) {
                throw missing("الكمية المطلوبة غير صالحة");
            }
            var onHand = onHand(clinicId, itemId, location);
            BigDecimal issued;
            boolean clamped;
            if (onHand.signum() <= 0) {
                issued = BigDecimal.ZERO;
                clamped = false;
            } else if (requestedQty.compareTo(onHand) > 0) {
                issued = onHand;
                clamped = true;
            } else {
                issued = requestedQty;
                clamped = false;
            }
            if (issued.signum() > 0) {
                recordMovement(clinicId, actor, itemId, location, issued.negate(), MovementReason.issue);
            }
            return new IssueResult(itemId, location, requestedQty, issued, clamped);
        });
    }

    @Override
    public void transfer(UUID clinicId, Actor actor, UUID itemId, LocationKind from, LocationKind to,
            BigDecimal qty) {
        transactionTemplate.executeWithoutResult(status -> {
            requireActiveActor(clinicId, actor);
            requireItem(clinicId, itemId);
            if (from == null || to == null || qty == null || qty.signum() <= 0) {
                throw missing("الكمية المراد تحويلها غير صالحة");
            }
            var onHand = onHand(clinicId, itemId, from);
            var moved = onHand.signum() <= 0 ? BigDecimal.ZERO : qty.min(onHand);
            if (moved.signum() > 0) {
                recordMovement(clinicId, actor, itemId, from, moved.negate(), MovementReason.transfer);
                recordMovement(clinicId, actor, itemId, to, moved, MovementReason.transfer);
            }
        });
    }

    @Override
    public void adjustCount(UUID clinicId, Actor actor, UUID itemId, LocationKind location,
            BigDecimal newCount) {
        transactionTemplate.executeWithoutResult(status -> {
            requireActiveActor(clinicId, actor);
            requireItem(clinicId, itemId);
            if (location == null || newCount == null || newCount.signum() < 0) {
                throw missing("العد الجديد غير صالح");
            }
            var delta = newCount.subtract(onHand(clinicId, itemId, location));
            if (delta.signum() != 0) {
                recordMovement(clinicId, actor, itemId, location, delta, MovementReason.count);
            }
        });
    }

    @Override
    public List<MovementEntry> ledger(UUID clinicId, int limit) {
        return transactionTemplate.execute(status -> dsl.select(
                STOCK_MOVEMENT.ID, STOCK_MOVEMENT.ITEM_ID, STOCK_MOVEMENT.LOCATION,
                STOCK_MOVEMENT.QTY_DELTA, STOCK_MOVEMENT.REASON, STOCK_MOVEMENT.CREATED_AT,
                INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM, EMPLOYEE.NAME)
                .from(STOCK_MOVEMENT)
                .join(INVENTORY_ITEM).on(STOCK_MOVEMENT.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .leftJoin(MEMBERSHIP).on(STOCK_MOVEMENT.CREATED_BY.eq(MEMBERSHIP.ID))
                .leftJoin(EMPLOYEE).on(MEMBERSHIP.EMPLOYEE_ID.eq(EMPLOYEE.ID))
                .where(STOCK_MOVEMENT.CLINIC_ID.eq(clinicId))
                .orderBy(STOCK_MOVEMENT.CREATED_AT.desc())
                .limit(limit)
                .fetch(r -> new MovementEntry(
                        r.get(STOCK_MOVEMENT.ID),
                        r.get(INVENTORY_ITEM.NAME),
                        r.get(INVENTORY_ITEM.UOM),
                        r.get(STOCK_MOVEMENT.LOCATION),
                        r.get(STOCK_MOVEMENT.QTY_DELTA),
                        r.get(STOCK_MOVEMENT.REASON).getLiteral(),
                        r.get(EMPLOYEE.NAME),
                        r.get(STOCK_MOVEMENT.CREATED_AT))));
    }

    private ChangeRequest requireChangeRequest(UUID clinicId, UUID requestId) {
        var r = dsl.select(
                INVENTORY_CHANGE_REQUEST.ID, INVENTORY_CHANGE_REQUEST.KIND,
                INVENTORY_CHANGE_REQUEST.ITEM_ID, INVENTORY_CHANGE_REQUEST.PAYLOAD,
                INVENTORY_CHANGE_REQUEST.REQUESTED_AT, INVENTORY_CHANGE_REQUEST.STATUS,
                INVENTORY_ITEM.NAME)
                .from(INVENTORY_CHANGE_REQUEST)
                .join(INVENTORY_ITEM).on(INVENTORY_CHANGE_REQUEST.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .where(INVENTORY_CHANGE_REQUEST.ID.eq(requestId))
                .and(INVENTORY_CHANGE_REQUEST.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (r == null) throw missing("طلب التعديل غير موجود");
        return toChangeRequest(r);
    }

    private ChangeRequest toChangeRequest(org.jooq.Record r) {
        var payload = r.get(INVENTORY_CHANGE_REQUEST.PAYLOAD);
        return new ChangeRequest(
                r.get(INVENTORY_CHANGE_REQUEST.ID),
                r.get(INVENTORY_CHANGE_REQUEST.KIND).getLiteral(),
                r.get(INVENTORY_CHANGE_REQUEST.ITEM_ID),
                r.get(INVENTORY_ITEM.NAME),
                payload == null ? null : parseProposed(payload),
                r.get(INVENTORY_CHANGE_REQUEST.REQUESTED_AT),
                r.get(INVENTORY_CHANGE_REQUEST.STATUS).getLiteral());
    }

    private com.clinicos.shared.jooq.tables.records.InventoryItemRecord requireItem(UUID clinicId, UUID itemId) {
        var item = dsl.selectFrom(INVENTORY_ITEM)
                .where(INVENTORY_ITEM.ID.eq(itemId))
                .and(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                .fetchOne();
        if (item == null) throw missing("الصنف غير موجود");
        return item;
    }

    private void applyEditPayload(UUID clinicId, UUID itemId, JSONB payload) {
        requireItem(clinicId, itemId);
        if (payload == null) throw missing("بيانات الطلب غير صالحة");
        var node = parse(payload);
        Map<TableField<?, ?>, Object> sets = new LinkedHashMap<>();
        if (node.has("name")) sets.put(INVENTORY_ITEM.NAME, node.get("name").asText());
        if (node.has("uom")) sets.put(INVENTORY_ITEM.UOM, node.get("uom").asText());
        if (node.has("unitsPerPack")) sets.put(INVENTORY_ITEM.UNITS_PER_PACK, integerValue(node.get("unitsPerPack")));
        if (node.has("unitCost")) sets.put(INVENTORY_ITEM.UNIT_COST, decimalValue(node.get("unitCost")));
        if (node.has("storeAlert")) sets.put(INVENTORY_ITEM.STORE_ALERT, integerValue(node.get("storeAlert")));
        if (node.has("trayAlert")) sets.put(INVENTORY_ITEM.TRAY_ALERT, integerValue(node.get("trayAlert")));
        if (!sets.isEmpty()) {
            dsl.update(INVENTORY_ITEM).set(sets)
                    .where(INVENTORY_ITEM.ID.eq(itemId))
                    .and(INVENTORY_ITEM.CLINIC_ID.eq(clinicId))
                    .execute();
        }
    }

    private void recordMovement(UUID clinicId, Actor actor, UUID itemId, LocationKind location,
            BigDecimal qtyDelta, MovementReason reason) {
        dsl.insertInto(STOCK_MOVEMENT)
                .set(STOCK_MOVEMENT.ID, UUID.randomUUID())
                .set(STOCK_MOVEMENT.CLINIC_ID, clinicId)
                .set(STOCK_MOVEMENT.ITEM_ID, itemId)
                .set(STOCK_MOVEMENT.LOCATION, location)
                .set(STOCK_MOVEMENT.QTY_DELTA, qtyDelta)
                .set(STOCK_MOVEMENT.REASON, reason)
                .set(STOCK_MOVEMENT.CREATED_BY, actor.membershipId())
                .execute();
    }

    private Field<BigDecimal> onHandAt(LocationKind location) {
        return dsl.select(coalesce(sum(STOCK_MOVEMENT.QTY_DELTA), BigDecimal.ZERO))
                .from(STOCK_MOVEMENT)
                .where(STOCK_MOVEMENT.ITEM_ID.eq(INVENTORY_ITEM.ID))
                .and(STOCK_MOVEMENT.LOCATION.eq(location))
                .asField();
    }

    private BigDecimal onHand(UUID clinicId, UUID itemId, LocationKind location) {
        var value = dsl.select(coalesce(sum(STOCK_MOVEMENT.QTY_DELTA), BigDecimal.ZERO))
                .from(STOCK_MOVEMENT)
                .where(STOCK_MOVEMENT.ITEM_ID.eq(itemId))
                .and(STOCK_MOVEMENT.CLINIC_ID.eq(clinicId))
                .and(STOCK_MOVEMENT.LOCATION.eq(location))
                .fetchOneInto(BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean belowAlert(BigDecimal onHand, Integer alert) {
        return alert != null && onHand != null && onHand.compareTo(BigDecimal.valueOf(alert)) < 0;
    }

    private Integer alertOf(org.jooq.Record r, LocationKind location) {
        return location == LocationKind.store
                ? r.get(INVENTORY_ITEM.STORE_ALERT)
                : r.get(INVENTORY_ITEM.TRAY_ALERT);
    }

    private void requireActiveActor(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null) throw missing("غير مصرح");
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP)
                .where(MEMBERSHIP.ID.eq(actor.membershipId()))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))))
            throw missing("غير مصرح");
    }

    private void requireManager(UUID clinicId, Actor actor) {
        if (actor == null || actor.membershipId() == null) throw missing("غير مصرح");
        if (!dsl.fetchExists(dsl.selectOne().from(MEMBERSHIP).join(ROLE).on(MEMBERSHIP.ROLE_ID.eq(ROLE.ID))
                .where(MEMBERSHIP.ID.eq(actor.membershipId()))
                .and(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .and(ROLE.CODE.in("owner", "manager"))))
            throw missing("غير مصرح — تتطلب صلاحيات مدير");
    }

    private JSONB serialize(ItemRequest request) {
        try {
            return JSONB.valueOf(JSON.writeValueAsString(request));
        } catch (Exception e) {
            return JSONB.valueOf("{}");
        }
    }

    private ItemRequest parseProposed(JSONB payload) {
        var node = parse(payload);
        return new ItemRequest(
                textValue(node.get("name")),
                textValue(node.get("uom")),
                integerValue(node.get("unitsPerPack")),
                decimalValue(node.get("unitCost")),
                integerValue(node.get("storeAlert")),
                integerValue(node.get("trayAlert")));
    }

    private JsonNode parse(JSONB payload) {
        try {
            return JSON.readTree(payload.data());
        } catch (Exception e) {
            throw missing("بيانات الطلب غير صالحة");
        }
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private Integer integerValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private BigDecimal decimalValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private IllegalArgumentException missing(String message) {
        return new IllegalArgumentException(message);
    }
}