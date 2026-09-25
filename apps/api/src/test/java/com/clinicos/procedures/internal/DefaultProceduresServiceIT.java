package com.clinicos.procedures.internal;

import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.clinicos.inventory.InventoryService;
import com.clinicos.inventory.InventoryService.Actor;
import com.clinicos.inventory.InventoryService.ItemRequest;
import com.clinicos.inventory.InventoryAnalyticsService;
import com.clinicos.procedures.ProceduresService;
import com.clinicos.procedures.ProceduresService.BomLineRequest;
import com.clinicos.procedures.ProceduresService.CaseDraft;
import com.clinicos.procedures.ProceduresService.CaseItemRequest;
import com.clinicos.procedures.ProceduresService.ProcedureRequest;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.ChangeRequestKind;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.enums.MovementReason;

@SpringBootTest(classes = Application.class)
class DefaultProceduresServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private ProceduresService proceduresService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InventoryAnalyticsService analyticsService;

    private UUID clinicA;
    private UUID clinicB;
    private Actor assistant;
    private Actor manager;
    private UUID employee;
    private UUID item;
    private UUID procedure;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Procedure A", "procedure-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Procedure B", "procedure-b-" + UUID.randomUUID());
            assistant = actor(connection, clinicA, "assistant");
            manager = actor(connection, clinicA, "manager");
            employee = TestFixtures.insertEmployee(connection, clinicA, "assistant employee");
            TestFixtures.linkMembershipToEmployee(connection, assistant.membershipId(), employee);
        }
        TenantContext.set(clinicA);
        item = inventoryService.createItem(clinicA, assistant,
                new ItemRequest("كمبوزيت", "عبوة", 1, new BigDecimal("10"), 2, 2)).id();
        procedure = proceduresService.createProcedure(clinicA, assistant,
                new ProcedureRequest("حشو", new BigDecimal("100"), new BigDecimal("20"), new BigDecimal("5"),
                        List.of(new BomLineRequest(item, new BigDecimal("2"))))).id();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void BRG28_caseCostIsMaterialsPlusLaborPlusDoctorFee() {
        seedTray(new BigDecimal("2"));

        var record = proceduresService.recordCase(clinicA, assistant,
                new CaseDraft(procedure, employee, "د. أحمد", "P-1", List.of()));

        assertThat(record.materialCost()).isEqualByComparingTo("20");
        assertThat(record.totalCost()).isEqualByComparingTo("45");
        assertThat(record.margin()).isEqualByComparingTo("55");
        assertThat(analyticsService.profit(clinicA, null, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.procedureName()).isEqualTo("حشو");
                    assertThat(row.materialCost()).isEqualByComparingTo("20");
                    assertThat(row.laborCost()).isEqualByComparingTo("20");
                    assertThat(row.doctorFee()).isEqualByComparingTo("5");
                    assertThat(row.margin()).isEqualByComparingTo("55");
                });
        assertThat(analyticsService.doctors(clinicA, null, null)).singleElement()
                .satisfies(row -> {
                    assertThat(row.doctorName()).isEqualTo("د. أحمد");
                    assertThat(row.materialCost()).isEqualByComparingTo("20");
                    assertThat(row.laborCost()).isEqualByComparingTo("20");
                    assertThat(row.doctorFee()).isEqualByComparingTo("5");
                    assertThat(row.margin()).isEqualByComparingTo("55");
                });
    }

    @Test
    void unitCostAtTimeIsFrozenWhenItemPriceChangesLater() {
        seedTray(new BigDecimal("2"));
        var record = proceduresService.recordCase(clinicA, assistant,
                new CaseDraft(procedure, employee, null, null, List.of()));
        var change = inventoryService.requestItemChange(clinicA, assistant, item, ChangeRequestKind.edit,
                new ItemRequest("كمبوزيت", "عبوة", 1, new BigDecimal("99"), 2, 2));
        inventoryService.applyItemChange(clinicA, manager, change.id(), true);

        var reloaded = proceduresService.cases(clinicA, employee, null, null).stream()
                .filter(it -> it.id().equals(record.id())).findFirst().orElseThrow();
        assertThat(reloaded.items().get(0).unitCostAtTime()).isEqualByComparingTo("10");
    }

    @Test
    void A3_caseIssueClampsToTrayOnHand() {
        seedTray(new BigDecimal("1"));

        var record = proceduresService.recordCase(clinicA, assistant,
                new CaseDraft(procedure, employee, null, null, List.of()));

        assertThat(record.items()).hasSize(1);
        assertThat(record.items().get(0).qty()).isEqualByComparingTo("1");
        assertThat(stock(item, LocationKind.tray)).isEqualByComparingTo("0");
    }

    @Test
    void caseIssueUsesStoreStockAfterTrayStockRunsOut() {
        seedStock(LocationKind.tray, new BigDecimal("1"));
        seedStock(LocationKind.store, new BigDecimal("1"));

        var record = proceduresService.recordCase(clinicA, assistant,
                new CaseDraft(procedure, employee, null, null, List.of()));

        assertThat(record.items()).singleElement().satisfies(caseItem -> {
            assertThat(caseItem.qty()).isEqualByComparingTo("2");
        });
        assertThat(stock(item, LocationKind.tray)).isEqualByComparingTo("0");
        assertThat(stock(item, LocationKind.store)).isEqualByComparingTo("0");
    }

    @Test
    void approvedCaseEditPersistsActualIssuedQuantityWhenStockIsInsufficient() {
        seedTray(new BigDecimal("1"));
        var record = proceduresService.recordCase(clinicA, assistant,
                new CaseDraft(procedure, employee, null, null, List.of()));

        var request = proceduresService.requestCaseChange(clinicA, assistant, record.id(), ChangeRequestKind.edit,
                List.of(new CaseItemRequest(item, new BigDecimal("2"))));
        proceduresService.decideChange(clinicA, manager, request, true);

        var reloaded = proceduresService.cases(clinicA, employee, null, null).stream()
                .filter(it -> it.id().equals(record.id())).findFirst().orElseThrow();
        assertThat(reloaded.items()).singleElement()
                .satisfies(item -> assertThat(item.qty()).isEqualByComparingTo("1"));
    }

    @Test
    void approvedCaseEditThatReducesQuantityWritesAdjustmentLedgerRow() {
        seedTray(new BigDecimal("5"));
        var record = proceduresService.recordCase(clinicA, assistant,
                new CaseDraft(procedure, employee, null, null, List.of(new CaseItemRequest(item, new BigDecimal("4")))));

        var request = proceduresService.requestCaseChange(clinicA, assistant, record.id(), ChangeRequestKind.edit,
                List.of(new CaseItemRequest(item, new BigDecimal("2"))));
        proceduresService.decideChange(clinicA, manager, request, true);

        assertThat(inventoryService.ledger(clinicA, 10))
                .anySatisfy(entry -> {
                    assertThat(entry.reason()).isEqualTo("adjustment");
                    assertThat(entry.qtyDelta()).isEqualByComparingTo("2");
                    assertThat(entry.location()).isEqualTo(LocationKind.tray);
                });
    }

    @Test
    void recordingACaseWritesNegativeTrayMovementsReferencingTheCase() throws Exception {
        seedTray(new BigDecimal("2"));

        var record = proceduresService.recordCase(clinicA, assistant,
                new CaseDraft(procedure, employee, null, null, List.of()));

        try (var connection = superuser()) {
            assertThat(DSL.using(connection, SQLDialect.POSTGRES).selectCount().from(STOCK_MOVEMENT)
                    .where(STOCK_MOVEMENT.REF_TYPE.eq("procedure_case"))
                    .and(STOCK_MOVEMENT.REF_ID.eq(record.id())).fetchOne(0, Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void BRG26_procedureEditIsQueuedNotApplied() {
        proceduresService.requestProcedureChange(clinicA, assistant, procedure, ChangeRequestKind.edit,
                new ProcedureRequest("حشو معدّل", new BigDecimal("150"), new BigDecimal("20"), new BigDecimal("5"), List.of()));

        assertThat(proceduresService.procedures(clinicA, false).get(0).name()).isEqualTo("حشو");
        assertThat(proceduresService.pendingChanges(clinicA)).hasSize(1);
    }

    @Test
    void approvedProcedureEditAppliesAfterManagerDecision() {
        var request = proceduresService.requestProcedureChange(clinicA, assistant, procedure, ChangeRequestKind.edit,
                new ProcedureRequest("حشو معدّل", new BigDecimal("150"), new BigDecimal("20"), new BigDecimal("5"), List.of()));

        proceduresService.decideChange(clinicA, manager, request, true);

        assertThat(proceduresService.procedures(clinicA, false).get(0).name()).isEqualTo("حشو معدّل");
    }

    @Test
    void clinicBCannotSeeClinicACases() {
        seedTray(new BigDecimal("2"));
        proceduresService.recordCase(clinicA, assistant, new CaseDraft(procedure, employee, null, null, List.of()));

        TenantContext.set(clinicB);
        assertThat(proceduresService.procedures(clinicB, false)).isEmpty();
        assertThat(proceduresService.cases(clinicB, null, null, null)).isEmpty();
    }

    @Test
    void suspendedMembershipIsRejectedOnDecideChange() throws Exception {
        Actor suspended;
        try (var connection = superuser()) {
            var membership = TestFixtures.insertMembership(connection, clinicA,
                    TestFixtures.insertUser(connection, clinicA, "suspended" + UUID.randomUUID(), "pw", "suspended"),
                    "manager", MembershipStatus.suspended);
            suspended = new Actor(membership, "manager");
        }

        assertThatThrownBy(() -> proceduresService.decideChange(clinicA, suspended, UUID.randomUUID(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("غير مصرح — تتطلب صلاحيات مدير");
    }

    private void seedTray(BigDecimal qty) {
        seedStock(LocationKind.tray, qty);
    }

    private void seedStock(LocationKind location, BigDecimal qty) {
        try (var connection = superuser()) {
            DSL.using(connection, SQLDialect.POSTGRES).insertInto(STOCK_MOVEMENT)
                    .set(STOCK_MOVEMENT.ID, UUID.randomUUID()).set(STOCK_MOVEMENT.CLINIC_ID, clinicA)
                    .set(STOCK_MOVEMENT.ITEM_ID, item).set(STOCK_MOVEMENT.LOCATION, location)
                    .set(STOCK_MOVEMENT.QTY_DELTA, qty).set(STOCK_MOVEMENT.REASON, MovementReason.receipt).execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BigDecimal stock(UUID itemId, LocationKind location) {
        try (var connection = superuser()) {
            return DSL.using(connection, SQLDialect.POSTGRES).select(org.jooq.impl.DSL.sum(STOCK_MOVEMENT.QTY_DELTA))
                    .from(STOCK_MOVEMENT).where(STOCK_MOVEMENT.ITEM_ID.eq(itemId)).and(STOCK_MOVEMENT.LOCATION.eq(location))
                    .fetchOneInto(BigDecimal.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Actor actor(Connection connection, UUID clinicId, String role) throws Exception {
        var membership = TestFixtures.insertMembership(connection, clinicId,
                TestFixtures.insertUser(connection, clinicId, role + UUID.randomUUID(), "password", "active"), role);
        return new Actor(membership, role);
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
