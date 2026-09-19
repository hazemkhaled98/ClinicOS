package com.clinicos.inventory.internal;

import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
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
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MovementReason;

@SpringBootTest(classes = Application.class)
class DefaultInventoryAnalyticsServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private InventoryAnalyticsService analytics;
    @Autowired
    private InventoryService inventory;

    private UUID clinicA;
    private UUID clinicB;
    private UUID item;
    private Actor actor;

    @BeforeEach
    void seed() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Analytics A", "analytics-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Analytics B", "analytics-b-" + UUID.randomUUID());
            actor = actor(connection, clinicA);
        }
        TenantContext.set(clinicA);
        item = inventory.createItem(clinicA, actor,
                new ItemRequest("كمبوزيت", "عبوة", 1, new BigDecimal("10"), 5, 2)).id();
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
