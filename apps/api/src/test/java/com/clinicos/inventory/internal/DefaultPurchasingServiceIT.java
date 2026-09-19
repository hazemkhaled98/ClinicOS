package com.clinicos.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.InventoryService.ItemRequest;
import com.clinicos.inventory.PurchasingService;
import com.clinicos.inventory.PurchasingService.OrderLineRequest;
import com.clinicos.inventory.PurchasingService.ReceiptLine;
import com.clinicos.inventory.PurchasingService.ReturnLineRequest;
import com.clinicos.inventory.PurchasingService.SupplierRequest;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MovementReason;

@SpringBootTest(classes = Application.class)
class DefaultPurchasingServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private PurchasingService purchasingService;

    @Autowired
    private InventoryService inventoryService;

    private UUID clinicA;
    private UUID clinicB;
    private Actor owner;
    private Actor manager;
    private Actor assistant;
    private UUID item;
    private UUID supplier;
    private UUID order;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic Purch A", "purch-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic Purch B", "purch-b-" + UUID.randomUUID());
            owner = actor(connection, clinicA, "owner");
            manager = actor(connection, clinicA, "manager");
            assistant = actor(connection, clinicA, "assistant");
        }
        TenantContext.set(clinicA);
        item = inventoryService.createItem(clinicA, assistant,
                new ItemRequest("كمبوزيت تصوير", "عبوة", 1, new BigDecimal("45.50"), 5, 3)).id();
        supplier = purchasingService.saveSupplier(clinicA, manager, null,
                new SupplierRequest("الريادة", "01000000000", "201000000000", 2, new BigDecimal("4.5"), false)).id();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void itemBelowStoreAlertAppearsInShortages() {
        TenantContext.set(clinicA);
        var shortages = purchasingService.shortages(clinicA);
        assertThat(shortages).anyMatch(s -> s.itemId().equals(item) && s.storeAlert() == 5);
    }

    @Test
    void shortagesAreEmptyOnceStockIsAboveAlert() {
        TenantContext.set(clinicA);
        placeAndReceiveFullQty();
        var shortages = purchasingService.shortages(clinicA);
        assertThat(shortages).noneMatch(s -> s.itemId().equals(item));
    }

    @Test
    void placingOrderFallsBackToItemCostAndBuildsWhatsappMessage() {
        TenantContext.set(clinicA);
        order = purchasingService.placeOrder(clinicA, assistant, supplier,
                List.of(new OrderLineRequest(item, new BigDecimal("10"), null))).id();

        var message = purchasingService.whatsappMessage(clinicA, order);
        assertThat(message).contains("كمبوزيت تصوير").contains("10");
    }

    @Test
    void creatingAnArchivedSupplierPersistsArchivedState() {
        var archived = purchasingService.saveSupplier(clinicA, manager, null,
                new SupplierRequest("مورد مؤرشف", null, null, 1, new BigDecimal("3"), true));

        assertThat(archived.archived()).isTrue();
        assertThat(purchasingService.suppliers(clinicA, false)).noneMatch(s -> s.id().equals(archived.id()));
        assertThat(purchasingService.suppliers(clinicA, true)).anyMatch(s -> s.id().equals(archived.id()) && s.archived());
    }

    @Test
    void receiveAddsStoreStockAndFinalizesTheOrder() {
        TenantContext.set(clinicA);
        order = placeOrder();

        var received = purchasingService.receive(clinicA, assistant, order,
                List.of(new ReceiptLine(orderLineId(order, 0), new BigDecimal("7"), "LOT-1", null)),
                insertAttachment(clinicA));

        assertThat(received.status()).isEqualTo("received");
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("7");
    }

    @Test
    void receiveWithoutInvoicePhotoFails() {
        TenantContext.set(clinicA);
        order = placeOrder();

        assertThatThrownBy(() -> purchasingService.receive(clinicA, assistant, order,
                List.of(new ReceiptLine(orderLineId(order, 0), new BigDecimal("7"), null, null)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("صورة الفاتورة");
    }

    @Test
    void receivingMoreThanOrderedIsRejected() {
        TenantContext.set(clinicA);
        order = placeOrder();

        assertThatThrownBy(() -> purchasingService.receive(clinicA, assistant, order,
                List.of(new ReceiptLine(orderLineId(order, 0), new BigDecimal("99"), null, null)),
                insertAttachment(clinicA)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أكبر من المطلوبة");
    }

    @Test
    void pendingReturnMovesNoStockUntilApproved() {
        TenantContext.set(clinicA);
        order = placeOrderAndReceive(10);

        var pending = purchasingService.requestReturn(clinicA, assistant, order,
                List.of(new ReturnLineRequest(orderLineId(order, 0), new BigDecimal("4"))));

        assertThat(pending.status()).isEqualTo("pending");
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("10");
        assertThat(purchasingService.pendingReturns(clinicA)).anyMatch(r -> r.id().equals(pending.id()));

        var approved = purchasingService.decideReturn(clinicA, manager, pending.id(), true);
        assertThat(approved.status()).isEqualTo("approved");
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("6");
    }

    @Test
    void rejectReturnLeavesStockIntactAndClearsTheQueue() {
        TenantContext.set(clinicA);
        order = placeOrderAndReceive(10);
        var pending = purchasingService.requestReturn(clinicA, assistant, order,
                List.of(new ReturnLineRequest(orderLineId(order, 0), new BigDecimal("4"))));

        var rejected = purchasingService.decideReturn(clinicA, manager, pending.id(), false);

        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("10");
        assertThat(purchasingService.pendingReturns(clinicA)).noneMatch(r -> r.id().equals(pending.id()));
    }

    @Test
    void decidingAReturnTwiceThrows() {
        TenantContext.set(clinicA);
        order = placeOrderAndReceive(10);
        var pending = purchasingService.requestReturn(clinicA, assistant, order,
                List.of(new ReturnLineRequest(orderLineId(order, 0), new BigDecimal("4"))));
        purchasingService.decideReturn(clinicA, manager, pending.id(), true);

        assertThatThrownBy(() -> purchasingService.decideReturn(clinicA, manager, pending.id(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnCannotExceedReceivedQuantity() {
        TenantContext.set(clinicA);
        order = placeOrderAndReceive(10);

        assertThatThrownBy(() -> purchasingService.requestReturn(clinicA, assistant, order,
                List.of(new ReturnLineRequest(orderLineId(order, 0), new BigDecimal("11")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أكبر من المتاح للإرجاع");
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("10");
    }

    @Test
    void nonManagerCannotDecideReturn() {
        TenantContext.set(clinicA);
        order = placeOrderAndReceive(10);
        var pending = purchasingService.requestReturn(clinicA, assistant, order,
                List.of(new ReturnLineRequest(orderLineId(order, 0), new BigDecimal("4"))));

        assertThatThrownBy(() -> purchasingService.decideReturn(clinicA, assistant, pending.id(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clinicBSeesNoSuppliersShortagesOrOrders() {
        TenantContext.set(clinicB);
        assertThat(purchasingService.suppliers(clinicB, false)).isEmpty();
        assertThat(purchasingService.shortages(clinicB)).isEmpty();
        assertThat(purchasingService.orders(clinicB, java.util.Set.of())).isEmpty();
    }

    private UUID placeOrder() {
        return purchasingService.placeOrder(clinicA, assistant, supplier,
                List.of(new OrderLineRequest(item, new BigDecimal("10"), null))).id();
    }

    private UUID placeOrderAndReceive(int qty) {
        var placed = placeOrder();
        purchasingService.receive(clinicA, assistant, placed,
                List.of(new ReceiptLine(orderLineId(placed, 0), new BigDecimal(qty), null, null)), insertAttachment(clinicA));
        return placed;
    }

    private void placeAndReceiveFullQty() {
        var placed = placeOrder();
        purchasingService.receive(clinicA, assistant, placed,
                List.of(new ReceiptLine(orderLineId(placed, 0), new BigDecimal("10"), null, null)), insertAttachment(clinicA));
    }

    private UUID orderLineId(UUID orderId, int index) {
        return purchasingService.orders(clinicA, java.util.Set.of(
                com.clinicos.shared.jooq.enums.PoStatus.placed,
                com.clinicos.shared.jooq.enums.PoStatus.received)).stream()
                .filter(o -> o.id().equals(orderId)).findFirst().orElseThrow().lines().get(index).id();
    }

    private BigDecimal stockOnHand(UUID itemId, LocationKind location) {
        try (var connection = superuser()) {
            var dslSuper = DSL.using(connection, org.jooq.SQLDialect.POSTGRES);
            BigDecimal sum = dslSuper.select(DSL.coalesce(DSL.sum(
                            com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT.QTY_DELTA), BigDecimal.ZERO))
                    .from(com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT)
                    .where(com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT.ITEM_ID.eq(itemId))
                    .and(com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT.LOCATION.eq(location))
                    .fetchOne(0, BigDecimal.class);
            return sum == null ? BigDecimal.ZERO : sum;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID insertAttachment(UUID clinicId) {
        try (var connection = superuser()) {
            var dslSuper = DSL.using(connection, org.jooq.SQLDialect.POSTGRES);
            var attachment = com.clinicos.shared.jooq.tables.Attachment.ATTACHMENT;
            return dslSuper.insertInto(attachment)
                    .set(attachment.CLINIC_ID, clinicId)
                    .set(attachment.STORAGE_KEY, "test-" + UUID.randomUUID())
                    .set(attachment.CONTENT_TYPE, "image/png")
                    .set(attachment.BYTE_SIZE, 1)
                    .returning(attachment.ID).fetchOne().getValue(attachment.ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Actor actor(Connection connection, UUID clinicId, String role) throws Exception {
        var membershipId = TestFixtures.insertMembership(connection, clinicId,
                TestFixtures.insertUser(connection, clinicId, role + UUID.randomUUID(), "password", "active"), role);
        var employeeId = TestFixtures.insertEmployee(connection, clinicId, role);
        TestFixtures.linkMembershipToEmployee(connection, membershipId, employeeId);
        return new Actor(membershipId, role);
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
