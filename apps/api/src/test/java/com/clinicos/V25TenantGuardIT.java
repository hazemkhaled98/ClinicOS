package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.InventoryItem.INVENTORY_ITEM;
import static com.clinicos.shared.jooq.tables.Procedure.PROCEDURE;
import static com.clinicos.shared.jooq.tables.ProcedureBom.PROCEDURE_BOM;
import static com.clinicos.shared.jooq.tables.ProcedureCase.PROCEDURE_CASE;
import static com.clinicos.shared.jooq.tables.ProcedureCaseItem.PROCEDURE_CASE_ITEM;
import static com.clinicos.shared.jooq.tables.PurchaseOrder.PURCHASE_ORDER;
import static com.clinicos.shared.jooq.tables.PurchaseOrderLine.PURCHASE_ORDER_LINE;
import static com.clinicos.shared.jooq.tables.Supplier.SUPPLIER;
import static com.clinicos.shared.jooq.tables.SupplierReturn.SUPPLIER_RETURN;
import static com.clinicos.shared.jooq.tables.SupplierReturnLine.SUPPLIER_RETURN_LINE;
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

/**
 * V25__inventory_child_tenant_guards.sql: the four inventory child tables that
 * carry no clinic_id column of their own are guarded by a "same clinic as the
 * referenced row" trigger. These tests attempt a cross-tenant reference from a
 * clinic-A parent row to a clinic-B referenced row and assert that the insert
 * is rejected by the guard (surfaced as a jOOQ DataAccessException through the
 * app's own app_rw connection).
 */
@SpringBootTest(classes = Application.class)
class V25TenantGuardIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID clinicA;
    private UUID clinicB;
    private UUID poA;
    private UUID poLineB;
    private UUID itemB;
    private UUID procedureA;
    private UUID caseA;
    private UUID supplierReturnA;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            var superDsl = DSL.using(connection, SQLDialect.POSTGRES);
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "guard-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "guard-b-" + UUID.randomUUID());

            UUID supplierA = insertSupplier(superDsl, clinicA);
            poA = insertPurchaseOrder(superDsl, clinicA, supplierA);
            procedureA = insertProcedure(superDsl, clinicA);
            var employeeA = TestFixtures.insertEmployee(connection, clinicA, "guard-employee");
            caseA = insertProcedureCase(superDsl, clinicA, procedureA, employeeA);
            supplierReturnA = insertSupplierReturn(superDsl, clinicA, poA, supplierA);

            UUID supplierB = insertSupplier(superDsl, clinicB);
            itemB = insertInventoryItem(superDsl, clinicB);
            UUID poB = insertPurchaseOrder(superDsl, clinicB, supplierB);
            poLineB = insertPurchaseOrderLine(superDsl, poB, itemB);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void purchaseOrderLineCannotReferenceOtherClinicItem() {
        assertCrossTenantRejected(() -> dsl.insertInto(PURCHASE_ORDER_LINE)
                .set(PURCHASE_ORDER_LINE.ID, UUID.randomUUID())
                .set(PURCHASE_ORDER_LINE.ORDER_ID, poA)
                .set(PURCHASE_ORDER_LINE.ITEM_ID, itemB)
                .set(PURCHASE_ORDER_LINE.QTY_ORDERED, new BigDecimal("1"))
                .set(PURCHASE_ORDER_LINE.UNIT_COST, new BigDecimal("10"))
                .execute());
    }

    @Test
    void procedureBomCannotReferenceOtherClinicItem() {
        assertCrossTenantRejected(() -> dsl.insertInto(PROCEDURE_BOM)
                .set(PROCEDURE_BOM.PROCEDURE_ID, procedureA)
                .set(PROCEDURE_BOM.ITEM_ID, itemB)
                .set(PROCEDURE_BOM.QTY, new BigDecimal("1"))
                .execute());
    }

    @Test
    void procedureCaseItemCannotReferenceOtherClinicItem() {
        assertCrossTenantRejected(() -> dsl.insertInto(PROCEDURE_CASE_ITEM)
                .set(PROCEDURE_CASE_ITEM.ID, UUID.randomUUID())
                .set(PROCEDURE_CASE_ITEM.PROCEDURE_CASE_ID, caseA)
                .set(PROCEDURE_CASE_ITEM.ITEM_ID, itemB)
                .set(PROCEDURE_CASE_ITEM.QTY, new BigDecimal("1"))
                .set(PROCEDURE_CASE_ITEM.UNIT_COST_AT_TIME, new BigDecimal("10"))
                .execute());
    }

    @Test
    void supplierReturnLineCannotReferenceOtherClinicPoLine() {
        assertCrossTenantRejected(() -> dsl.insertInto(SUPPLIER_RETURN_LINE)
                .set(SUPPLIER_RETURN_LINE.ID, UUID.randomUUID())
                .set(SUPPLIER_RETURN_LINE.SUPPLIER_RETURN_ID, supplierReturnA)
                .set(SUPPLIER_RETURN_LINE.PURCHASE_ORDER_LINE_ID, poLineB)
                .set(SUPPLIER_RETURN_LINE.QTY, new BigDecimal("1"))
                .execute());
    }

    private void assertCrossTenantRejected(Runnable insert) {
        TenantContext.set(clinicA);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> insert.run()))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }

    private static UUID insertSupplier(DSLContext superDsl, UUID clinicId) {
        return superDsl.insertInto(SUPPLIER, SUPPLIER.ID, SUPPLIER.CLINIC_ID, SUPPLIER.NAME)
                .values(UUID.randomUUID(), clinicId, "supplier")
                .returningResult(SUPPLIER.ID).fetchOne(SUPPLIER.ID);
    }

    private static UUID insertPurchaseOrder(DSLContext superDsl, UUID clinicId, UUID supplierId) {
        return superDsl.insertInto(PURCHASE_ORDER, PURCHASE_ORDER.ID, PURCHASE_ORDER.CLINIC_ID, PURCHASE_ORDER.SUPPLIER_ID)
                .values(UUID.randomUUID(), clinicId, supplierId)
                .returningResult(PURCHASE_ORDER.ID).fetchOne(PURCHASE_ORDER.ID);
    }

    private static UUID insertPurchaseOrderLine(DSLContext superDsl, UUID orderId, UUID itemId) {
        return superDsl.insertInto(PURCHASE_ORDER_LINE, PURCHASE_ORDER_LINE.ID, PURCHASE_ORDER_LINE.ORDER_ID,
                PURCHASE_ORDER_LINE.ITEM_ID, PURCHASE_ORDER_LINE.QTY_ORDERED, PURCHASE_ORDER_LINE.UNIT_COST)
                .values(UUID.randomUUID(), orderId, itemId, new BigDecimal("1"), new BigDecimal("10"))
                .returningResult(PURCHASE_ORDER_LINE.ID).fetchOne(PURCHASE_ORDER_LINE.ID);
    }

    private static UUID insertInventoryItem(DSLContext superDsl, UUID clinicId) {
        return superDsl.insertInto(INVENTORY_ITEM, INVENTORY_ITEM.ID, INVENTORY_ITEM.CLINIC_ID,
                INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM, INVENTORY_ITEM.UNIT_COST)
                .values(UUID.randomUUID(), clinicId, "other-clinic-item", "عبوة", new BigDecimal("10"))
                .returningResult(INVENTORY_ITEM.ID).fetchOne(INVENTORY_ITEM.ID);
    }

    private static UUID insertProcedure(DSLContext superDsl, UUID clinicId) {
        return superDsl.insertInto(PROCEDURE, PROCEDURE.ID, PROCEDURE.CLINIC_ID, PROCEDURE.NAME, PROCEDURE.PRICE)
                .values(UUID.randomUUID(), clinicId, "procedure", new BigDecimal("100"))
                .returningResult(PROCEDURE.ID).fetchOne(PROCEDURE.ID);
    }

    private static UUID insertProcedureCase(DSLContext superDsl, UUID clinicId, UUID procedureId, UUID employeeId) {
        return superDsl.insertInto(PROCEDURE_CASE, PROCEDURE_CASE.ID, PROCEDURE_CASE.CLINIC_ID,
                PROCEDURE_CASE.PROCEDURE_ID, PROCEDURE_CASE.EMPLOYEE_ID)
                .values(UUID.randomUUID(), clinicId, procedureId, employeeId)
                .returningResult(PROCEDURE_CASE.ID).fetchOne(PROCEDURE_CASE.ID);
    }

    private static UUID insertSupplierReturn(DSLContext superDsl, UUID clinicId, UUID poId, UUID supplierId) {
        return superDsl.insertInto(SUPPLIER_RETURN, SUPPLIER_RETURN.ID, SUPPLIER_RETURN.CLINIC_ID,
                SUPPLIER_RETURN.PURCHASE_ORDER_ID, SUPPLIER_RETURN.SUPPLIER_ID)
                .values(UUID.randomUUID(), clinicId, poId, supplierId)
                .returningResult(SUPPLIER_RETURN.ID).fetchOne(SUPPLIER_RETURN.ID);
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}