package com.clinicos.inventory.internal;

import static com.clinicos.shared.jooq.tables.PurchaseOrder.PURCHASE_ORDER;
import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.inventory.InventoryAnalyticsService;
import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.InventoryService.ItemRequest;
import com.clinicos.inventory.PurchasingService;
import com.clinicos.inventory.PurchasingService.OrderLineRequest;
import com.clinicos.inventory.PurchasingService.ReceiptLine;
import com.clinicos.inventory.PurchasingService.ReturnLineRequest;
import com.clinicos.inventory.PurchasingService.SupplierRequest;
import com.clinicos.procedures.ProceduresService;
import com.clinicos.procedures.ProceduresService.BomLineRequest;
import com.clinicos.procedures.ProceduresService.CaseDraft;
import com.clinicos.procedures.ProceduresService.ProcedureRequest;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MovementReason;

@SpringBootTest(classes = Application.class)
class DefaultInventoryAnalyticsServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private InventoryAnalyticsService analytics;
    @Autowired
    private InventoryService inventory;
    @Autowired
    private PurchasingService purchasing;
    @Autowired
    private ProceduresService procedures;

    private UUID clinicA;
    private UUID clinicB;
    private UUID item;
    private UUID supplier;
    private UUID employee;
    private Actor actor;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Analytics A", "analytics-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Analytics B", "analytics-b-" + UUID.randomUUID());
            actor = actor(connection, clinicA);
            employee = TestFixtures.insertEmployee(connection, clinicA, "analytics employee");
            TestFixtures.linkMembershipToEmployee(connection, actor.membershipId(), employee);
        }
        TenantContext.set(clinicA);
        item = inventory.createItem(clinicA, actor,
                new ItemRequest("كمبوزيت", "عبوة", 1, new BigDecimal("10"), 5, 2)).id();
        supplier = purchasing.saveSupplier(clinicA, actor, null,
                new SupplierRequest("الريادة", "01000000000", "201000000000", 2, new BigDecimal("4.5"), false)).id();
        movement(clinicA, item, LocationKind.store, new BigDecimal("10"), MovementReason.receipt);
        movement(clinicA, item, LocationKind.store, new BigDecimal("-3"), MovementReason.issue);
        movement(clinicA, item, LocationKind.store, new BigDecimal("-1"), MovementReason.adjustment);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void dashboardConsumptionAndWasteUseTenantLedger() {
        var dashboard = analytics.dashboard(clinicA);
        assertThat(dashboard.storeValue()).isEqualByComparingTo("60");
        assertThat(analytics.consumption(clinicA, null, null)).singleElement()
                .extracting(InventoryAnalyticsService.ConsumptionRow::consumed)
                .satisfies(value -> assertThat((BigDecimal) value).isEqualByComparingTo("3"));
        assertThat(analytics.waste(clinicA, null, null)).singleElement()
                .extracting(InventoryAnalyticsService.WasteRow::quantity)
                .satisfies(value -> assertThat((BigDecimal) value).isEqualByComparingTo("1"));
    }

    @Test
    void everyAnalyticsScreenReturnsReadOnlyRowsAndOtherClinicIsExcluded() {
        assertThat(analytics.profit(clinicA, null, null)).isEmpty();
        assertThat(analytics.doctors(clinicA, null, null)).isEmpty();
        assertThat(analytics.suppliers(clinicA, null, null)).isEmpty();
        assertThat(analytics.itemPrices(clinicA, null, null)).isEmpty();
        assertThat(analytics.dashboard(clinicB).storeValue()).isEqualByComparingTo("0");
    }

    @Test
    void profitAndDoctorsSplitMaterialLaborAndDoctorFeeAndGroupByProcedureAndDoctor() {
        seedCase(item, "حشو", "د. أحمد", "100", "20", "5", "1");
        seedCase(item, "حشو", null, "100", "20", "5", "1");
        seedCase(item2(), "خلع", "د. أحمد", "150", "30", "10", "2");

        assertThat(analytics.profit(clinicA, null, null))
                .filteredOn(InventoryAnalyticsService.ProfitRow::procedureName, "حشو").singleElement()
                .satisfies(row -> {
                    assertThat(row.revenue()).isEqualByComparingTo("200");
                    assertThat(row.materialCost()).isEqualByComparingTo("20");
                    assertThat(row.laborCost()).isEqualByComparingTo("40");
                    assertThat(row.doctorFee()).isEqualByComparingTo("10");
                    assertThat(row.margin()).isEqualByComparingTo("130");
                    assertThat(row.materialCost().scale()).isEqualTo(2);
                    assertThat(row.margin().scale()).isEqualTo(2);
                    assertThat(row.caseCount()).isEqualTo(2);
                });
        assertThat(analytics.profit(clinicA, null, null))
                .filteredOn(InventoryAnalyticsService.ProfitRow::procedureName, "خلع").singleElement()
                .satisfies(row -> {
                    assertThat(row.revenue()).isEqualByComparingTo("150");
                    assertThat(row.materialCost()).isEqualByComparingTo("16");
                    assertThat(row.laborCost()).isEqualByComparingTo("30");
                    assertThat(row.doctorFee()).isEqualByComparingTo("10");
                    assertThat(row.margin()).isEqualByComparingTo("94");
                });

        assertThat(analytics.doctors(clinicA, null, null))
                .filteredOn(InventoryAnalyticsService.DoctorRow::doctorName, "د. أحمد").singleElement()
                .satisfies(row -> {
                    assertThat(row.caseCount()).isEqualTo(2);
                    assertThat(row.revenue()).isEqualByComparingTo("250");
                    assertThat(row.materialCost()).isEqualByComparingTo("26");
                    assertThat(row.laborCost()).isEqualByComparingTo("50");
                    assertThat(row.doctorFee()).isEqualByComparingTo("15");
                    assertThat(row.margin()).isEqualByComparingTo("159");
                });
        assertThat(analytics.doctors(clinicA, null, null))
                .filteredOn(InventoryAnalyticsService.DoctorRow::doctorName, "غير محدد").singleElement()
                .satisfies(row -> {
                    assertThat(row.caseCount()).isEqualTo(1);
                    assertThat(row.revenue()).isEqualByComparingTo("100");
                    assertThat(row.materialCost()).isEqualByComparingTo("10");
                    assertThat(row.laborCost()).isEqualByComparingTo("20");
                    assertThat(row.doctorFee()).isEqualByComparingTo("5");
                    assertThat(row.margin()).isEqualByComparingTo("65");
                });
    }

    @Test
    void suppliersComputeSpendAverageCostLeadDaysAndReturnRatePerSupplier() {
        var secondItem = item2();
        var supplier2 = purchasing.saveSupplier(clinicA, actor, null,
                new SupplierRequest("تحفة", null, null, 1, new BigDecimal("4.5"), false)).id();
        var order1 = placeAndReceive(item, supplier, "10", "5", "LOT-A");
        var order2 = placeAndReceive(secondItem, supplier, "2", "8", null);
        var order3 = placeAndReceive(secondItem, supplier2, "3", "20", null);
        shiftOrdersBack(4, order1, order2, order3);
        var ret = purchasing.requestReturn(clinicA, actor, order1,
                List.of(new ReturnLineRequest(orderLineId(order1, 0), BigDecimal.ONE)));
        purchasing.decideReturn(clinicA, actor, ret.id(), true);

        assertThat(analytics.suppliers(clinicA, null, null))
                .filteredOn(InventoryAnalyticsService.SupplierRow::supplierName, "الريادة").singleElement()
                .satisfies(row -> {
                    assertThat(row.orderCount()).isEqualTo(2);
                    assertThat(row.spend()).isEqualByComparingTo("66");
                    assertThat(row.averageUnitCost()).isEqualByComparingTo("6.50");
                    assertThat(row.averageLeadDays()).isEqualByComparingTo("4");
                    assertThat(row.returnRate()).isEqualByComparingTo("0.0833");
                });
        assertThat(analytics.suppliers(clinicA, null, null))
                .filteredOn(InventoryAnalyticsService.SupplierRow::supplierName, "تحفة").singleElement()
                .satisfies(row -> {
                    assertThat(row.orderCount()).isEqualTo(1);
                    assertThat(row.spend()).isEqualByComparingTo("60");
                    assertThat(row.averageUnitCost()).isEqualByComparingTo("20.00");
                    assertThat(row.returnRate()).isEqualByComparingTo("0.0000");
                });

        assertThat(analytics.itemPrices(clinicA, null, null)).hasSize(3)
                .anySatisfy(row -> {
                    assertThat(row.itemName()).isEqualTo("كمبوزيت");
                    assertThat(row.supplierName()).isEqualTo("الريادة");
                    assertThat(row.unitCost()).isEqualByComparingTo("5");
                });
    }

    private UUID item2() {
        return inventory.createItem(clinicA, actor,
                new ItemRequest("مادة حشو", "عبوة", 1, new BigDecimal("8"), 2, 2)).id();
    }

    private void seedCase(UUID procedureItem, String name, String doctorName, String price,
            String labor, String fee, String bomQty) {
        var procedureId = procedures.createProcedure(clinicA, actor, new ProcedureRequest(name,
                new BigDecimal(price), new BigDecimal(labor), new BigDecimal(fee),
                List.of(new BomLineRequest(procedureItem, new BigDecimal(bomQty))))).id();
        movement(clinicA, procedureItem, LocationKind.tray, new BigDecimal(bomQty), MovementReason.receipt);
        procedures.recordCase(clinicA, actor, new CaseDraft(procedureId, employee, doctorName,
                "P-" + UUID.randomUUID(), List.of()));
    }

    private UUID placeAndReceive(UUID itemId, UUID supplierId, String qty, String unitCost, String lot) {
        var orderId = purchasing.placeOrder(clinicA, actor, supplierId,
                List.of(new OrderLineRequest(itemId, new BigDecimal(qty), new BigDecimal(unitCost)))).id();
        purchasing.receive(clinicA, actor, orderId,
                List.of(new ReceiptLine(orderLineId(orderId, 0), new BigDecimal(qty), lot, null)),
                insertAttachment(clinicA));
        return orderId;
    }

    private UUID orderLineId(UUID orderId, int index) {
        return purchasing.orders(clinicA, java.util.Set.of(
                com.clinicos.shared.jooq.enums.PoStatus.placed,
                com.clinicos.shared.jooq.enums.PoStatus.received)).stream()
                .filter(o -> o.id().equals(orderId)).findFirst().orElseThrow().lines().get(index).id();
    }

    private void shiftOrdersBack(int days, UUID... ids) {
        try (var connection = superuser()) {
            DSL.using(connection, SQLDialect.POSTGRES)
                    .update(PURCHASE_ORDER)
                    .set(PURCHASE_ORDER.PLACED_AT, java.time.OffsetDateTime.now().minusDays(days).minusMinutes(30))
                    .where(PURCHASE_ORDER.ID.in(ids))
                    .execute();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private UUID insertAttachment(UUID clinicId) {
        try (var connection = superuser()) {
            var attachment = com.clinicos.shared.jooq.tables.Attachment.ATTACHMENT;
            return DSL.using(connection, SQLDialect.POSTGRES)
                    .insertInto(attachment)
                    .set(attachment.CLINIC_ID, clinicId)
                    .set(attachment.STORAGE_KEY, "test-" + UUID.randomUUID())
                    .set(attachment.CONTENT_TYPE, "image/png")
                    .set(attachment.BYTE_SIZE, 1)
                    .returning(attachment.ID).fetchOne().get(attachment.ID);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void movement(UUID clinic, UUID itemId, LocationKind location, BigDecimal qty, MovementReason reason) {
        try (var connection = superuser()) {
            DSL.using(connection, SQLDialect.POSTGRES).insertInto(STOCK_MOVEMENT)
                    .set(STOCK_MOVEMENT.ID, UUID.randomUUID()).set(STOCK_MOVEMENT.CLINIC_ID, clinic)
                    .set(STOCK_MOVEMENT.ITEM_ID, itemId).set(STOCK_MOVEMENT.LOCATION, location)
                    .set(STOCK_MOVEMENT.QTY_DELTA, qty).set(STOCK_MOVEMENT.REASON, reason).execute();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private Actor actor(Connection connection, UUID clinic) throws Exception {
        var user = TestFixtures.insertUser(connection, clinic, "analytics-" + UUID.randomUUID(), "password", "active");
        return new Actor(TestFixtures.insertMembership(connection, clinic, user, "manager"), "manager");
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
