package com.clinicos.inventory.internal;

import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.InventoryService.ItemRequest;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.enums.MovementReason;

@SpringBootTest(classes = Application.class)
class DefaultInventoryServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID clinicA;
    private UUID clinicB;
    private Actor owner;
    private Actor manager;
    private Actor assistant;
    private UUID item;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
            owner = actor(connection, clinicA, "owner");
            manager = actor(connection, clinicA, "manager");
            assistant = actor(connection, clinicA, "assistant");
        }
        TenantContext.set(clinicA);
        item = newItem();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void onHandIsDerivedBySummingMovementsPerItemAndLocation() {
        TenantContext.set(clinicA);
        seedMovement(clinicA, item, LocationKind.store, new BigDecimal("10"), MovementReason.receipt);
        seedMovement(clinicA, item, LocationKind.store, new BigDecimal("-3"), MovementReason.issue);
        seedMovement(clinicA, item, LocationKind.tray, new BigDecimal("5"), MovementReason.receipt);

        var all = inventoryService.items(clinicA, false).stream()
                .filter(i -> i.id().equals(item)).findFirst().orElseThrow();
        assertThat(all.onHandStore()).isEqualByComparingTo("7");
        assertThat(all.onHandTray()).isEqualByComparingTo("5");
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("7");
    }

    @Test
    void A3_issueClampsToOnHandAndRecordsTheActualIssued() {
        TenantContext.set(clinicA);
        seedMovement(clinicA, item, LocationKind.store, new BigDecimal("10"), MovementReason.receipt);

        var clamped = inventoryService.issue(clinicA, assistant, item, LocationKind.store, new BigDecimal("15"));

        assertThat(clamped.clamped()).isTrue();
        assertThat(clamped.issuedQty()).isEqualByComparingTo("10");
        var after = inventoryService.items(clinicA, false).stream()
                .filter(i -> i.id().equals(item)).findFirst().orElseThrow();
        assertThat(after.onHandStore()).isEqualByComparingTo("0");
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("0");
    }

    @Test
    void A3_issueFromZeroOnHandWritesNoMovementRow() {
        TenantContext.set(clinicA);
        long before = movementCount(item);

        var result = inventoryService.issue(clinicA, assistant, item, LocationKind.store, new BigDecimal("5"));

        assertThat(result.issuedQty()).isEqualByComparingTo("0");
        assertThat(result.clamped()).isFalse();
        assertThat(movementCount(item)).isEqualTo(before);
    }

    @Test
    void adjustCountWritesOneCompensatingRowAndSkipsWhenDeltaIsZero() {
        TenantContext.set(clinicA);
        seedMovement(clinicA, item, LocationKind.store, new BigDecimal("5"), MovementReason.receipt);
        long before = movementCount(item);

        inventoryService.adjustCount(clinicA, assistant, item, LocationKind.store, new BigDecimal("8"));

        long afterRaise = movementCount(item);
        assertThat(afterRaise).isEqualTo(before + 1);
        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("8");

        inventoryService.adjustCount(clinicA, assistant, item, LocationKind.store, new BigDecimal("8"));

        assertThat(movementCount(item)).isEqualTo(afterRaise);
    }

    @Test
    void stockMovementIsAppendOnly() {
        TenantContext.set(clinicA);
        seedMovement(clinicA, item, LocationKind.store, new BigDecimal("10"), MovementReason.receipt);
        var movementId = movementIdOf(item);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                dsl.update(STOCK_MOVEMENT)
                        .set(STOCK_MOVEMENT.QTY_DELTA, new BigDecimal("99"))
                        .where(STOCK_MOVEMENT.ID.eq(movementId))
                        .execute()))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }

    @Test
    void BRG26_assistantFilesEditRequestWithoutChangingTheItem() {
        TenantContext.set(clinicA);
        var proposed = new ItemRequest("اسم معدّل", "عبوة", null, new BigDecimal("60"), null, null);

        var request = inventoryService.requestItemChange(clinicA, assistant, item,
                ChangeRequestKind.edit, proposed);

        assertThat(request.status()).isEqualTo("pending");
        assertThat(request.kind()).isEqualTo("edit");
        var refetched = inventoryService.items(clinicA, false).stream()
                .filter(i -> i.id().equals(item)).findFirst().orElseThrow();
        assertThat(refetched.name()).isEqualTo("كمبوزيت");
        assertThat(refetched.unitCost()).isEqualByComparingTo("45.50");
    }

    @Test
    void BRG26_nonManagerCannotApplyAnItemChange() {
        TenantContext.set(clinicA);
        var request = inventoryService.requestItemChange(clinicA, assistant, item,
                ChangeRequestKind.edit, new ItemRequest("اسم معدّل", "عبوة", null, new BigDecimal("60"), null, null));

        assertThatThrownBy(() -> inventoryService.applyItemChange(clinicA, assistant, request.id(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BRG26_applyItemChangeTwiceThrows() {
        TenantContext.set(clinicA);
        var request = inventoryService.requestItemChange(clinicA, assistant, item,
                ChangeRequestKind.edit, new ItemRequest("اسم معدّل", "عبوة", null, new BigDecimal("60"), null, null));

        var approved = inventoryService.applyItemChange(clinicA, manager, request.id(), true);
        assertThat(approved.status()).isEqualTo("approved");

        assertThatThrownBy(() -> inventoryService.applyItemChange(clinicA, manager, request.id(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void A1_rejectedEditLeavesTheItemUntouched() {
        TenantContext.set(clinicA);
        var request = inventoryService.requestItemChange(clinicA, assistant, item,
                ChangeRequestKind.edit, new ItemRequest("اسم معدّل", "عبوة", null, new BigDecimal("60"), null, null));

        var rejected = inventoryService.applyItemChange(clinicA, manager, request.id(), false);

        assertThat(rejected.status()).isEqualTo("rejected");
        var refetched = inventoryService.items(clinicA, false).stream()
                .filter(i -> i.id().equals(item)).findFirst().orElseThrow();
        assertThat(refetched.name()).isEqualTo("كمبوزيت");
        assertThat(refetched.unitCost()).isEqualByComparingTo("45.50");
    }

    @Test
    void A2_itemBelowAlertsIsFlagged() {
        TenantContext.set(clinicA);
        seedMovement(clinicA, item, LocationKind.store, new BigDecimal("2"), MovementReason.receipt);

        var flagged = inventoryService.items(clinicA, false).stream()
                .filter(i -> i.id().equals(item)).findFirst().orElseThrow();

        assertThat(flagged.belowStoreAlert()).isTrue();
        assertThat(flagged.belowTrayAlert()).isTrue();
    }

    @Test
    void clinicBCannotSeeClinicAItemsOrStock() {
        TenantContext.set(clinicB);

        assertThat(inventoryService.items(clinicB, false)).isEmpty();
        assertThat(inventoryService.stock(clinicB, LocationKind.store)).isEmpty();
    }

    @Test
    void transferClampsToSourceOnHandAndWritesTwoRows() {
        TenantContext.set(clinicA);
        seedMovement(clinicA, item, LocationKind.store, new BigDecimal("10"), MovementReason.receipt);
        long before = movementCount(item);

        inventoryService.transfer(clinicA, assistant, item, LocationKind.store, LocationKind.tray, new BigDecimal("15"));

        assertThat(stockOnHand(item, LocationKind.store)).isEqualByComparingTo("0");
        assertThat(stockOnHand(item, LocationKind.tray)).isEqualByComparingTo("10");
        assertThat(movementCount(item)).isEqualTo(before + 2);
    }

    @Test
    void transferWithInvalidQtyThrows() {
        TenantContext.set(clinicA);
        assertThatThrownBy(() -> inventoryService.transfer(clinicA, assistant, item,
                LocationKind.store, LocationKind.tray, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("الكمية المراد تحويلها غير صالحة");
    }

    @Test
    void adjustForWritesMovementWithActorName() {
        TenantContext.set(clinicA);
        inventoryService.adjustFor(clinicA, assistant, item, LocationKind.store,
                new BigDecimal("4"), MovementReason.receipt, null, null);

        var entry = inventoryService.ledger(clinicA, 1).get(0);
        assertThat(entry.itemName()).isEqualTo("كمبوزيت");
        assertThat(entry.qtyDelta()).isEqualByComparingTo("4");
        assertThat(entry.reason()).isEqualTo("receipt");
        assertThat(entry.actorName()).isEqualTo("assistant");
    }

    @Test
    void adjustForWithMissingReasonThrows() {
        TenantContext.set(clinicA);
        assertThatThrownBy(() -> inventoryService.adjustFor(clinicA, assistant, item,
                LocationKind.store, new BigDecimal("4"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("حركة المخزون غير صالحة");
    }

    @Test
    void pendingChangeRequestsAndApprovalsListOnlyPending() {
        TenantContext.set(clinicA);
        inventoryService.requestItemChange(clinicA, assistant, item, ChangeRequestKind.edit,
                new ItemRequest("اسم معدّل", "عبوة", null, new BigDecimal("55"), null, null));

        assertThat(inventoryService.pendingChangeRequests(clinicA)).hasSize(1)
                .allMatch(c -> c.status().equals("pending"));
        assertThat(inventoryService.pendingApprovals(clinicA)).hasSize(1)
                .allMatch(a -> a.source().equals("change_request") && a.entity().equals("item"));
    }

    @Test
    void createItemMissingFieldsThrowsArabicMessage() {
        TenantContext.set(clinicA);
        assertThatThrownBy(() -> inventoryService.createItem(clinicA, assistant,
                new ItemRequest(" ", "عبوة", 1, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("الاسم ووحدة القياس والتكلفة مطلوبة");
    }

    @Test
    void requestItemChangeMissingKindOrPayloadThrowsArabicMessages() {
        TenantContext.set(clinicA);
        assertThatThrownBy(() -> inventoryService.requestItemChange(clinicA, assistant, item, null,
                new ItemRequest("اسم", "عبوة", null, new BigDecimal("5"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("نوع الطلب غير محدد");
        assertThatThrownBy(() -> inventoryService.requestItemChange(clinicA, assistant, item,
                ChangeRequestKind.edit, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("البيانات المقترحة للتعديل مطلوبة");
    }

    @Test
    void suspendedMembershipIsRejectedOnEveryEntryPoint() throws Exception {
        TenantContext.set(clinicA);
        Actor suspended;
        try (var connection = superuser()) {
            var membership = TestFixtures.insertMembership(connection, clinicA,
                    TestFixtures.insertUser(connection, clinicA, "suspended" + UUID.randomUUID(), "pw", "suspended"),
                    "manager", MembershipStatus.suspended);
            suspended = new Actor(membership, "manager");
        }

        assertThatThrownBy(() -> inventoryService.applyItemChange(clinicA, suspended, UUID.randomUUID(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("غير مصرح — تتطلب صلاحيات مدير");
        assertThatThrownBy(() -> inventoryService.createItem(clinicA, suspended,
                new ItemRequest("س", "عبوة", 1, new BigDecimal("1"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("غير مصرح");
        assertThatThrownBy(() -> inventoryService.transfer(clinicA, suspended, item,
                LocationKind.store, LocationKind.tray, new BigDecimal("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID newItem() {
        var created = inventoryService.createItem(clinicA, assistant,
                new ItemRequest("كمبوزيت", "عبوة", 1, new BigDecimal("45.50"), 5, 3));
        return created.id();
    }

    private void seedMovement(UUID clinicId, UUID itemId, LocationKind location, BigDecimal qty,
            MovementReason reason) {
        try (var connection = superuser()) {
            DSL.using(connection, SQLDialect.POSTGRES)
                    .insertInto(STOCK_MOVEMENT, STOCK_MOVEMENT.ID, STOCK_MOVEMENT.CLINIC_ID,
                            STOCK_MOVEMENT.ITEM_ID, STOCK_MOVEMENT.LOCATION, STOCK_MOVEMENT.QTY_DELTA,
                            STOCK_MOVEMENT.REASON)
                    .values(UUID.randomUUID(), clinicId, itemId, location, qty, reason)
                    .execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID movementIdOf(UUID itemId) {
        try (var connection = superuser()) {
            return DSL.using(connection, SQLDialect.POSTGRES)
                    .select(STOCK_MOVEMENT.ID).from(STOCK_MOVEMENT)
                    .where(STOCK_MOVEMENT.ITEM_ID.eq(itemId)).limit(1)
                    .fetchOne(STOCK_MOVEMENT.ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long movementCount(UUID itemId) {
        try (var connection = superuser()) {
            return DSL.using(connection, SQLDialect.POSTGRES)
                    .fetchCount(STOCK_MOVEMENT, STOCK_MOVEMENT.ITEM_ID.eq(itemId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BigDecimal stockOnHand(UUID itemId, LocationKind location) {
        try (var connection = superuser()) {
            var dslSuper = DSL.using(connection, SQLDialect.POSTGRES);
            BigDecimal sum = dslSuper.select(DSL.coalesce(DSL.sum(STOCK_MOVEMENT.QTY_DELTA), BigDecimal.ZERO))
                    .from(STOCK_MOVEMENT)
                    .where(STOCK_MOVEMENT.ITEM_ID.eq(itemId))
                    .and(STOCK_MOVEMENT.LOCATION.eq(location))
                    .fetchOne(0, BigDecimal.class);
            return sum == null ? BigDecimal.ZERO : sum;
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