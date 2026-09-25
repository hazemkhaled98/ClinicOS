package com.clinicos.ui;

import static com.clinicos.shared.jooq.tables.InventoryItem.INVENTORY_ITEM;
import static com.clinicos.shared.jooq.tables.StockMovement.STOCK_MOVEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.clinicos.PostgresTestSupport;
import com.clinicos.TestFixtures;
import com.clinicos.shared.jooq.enums.LocationKind;
import com.clinicos.shared.jooq.enums.MovementReason;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * UC-008: inventory foundation, exercised end-to-end through a browser as the
 * slice's manual walkthrough. An assistant issues more than the on-hand (clamp),
 * files an item edit that waits for approval, and the owner then approves it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC008InventoryFoundationIT extends AbstractBrowserIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void migrateAndProvisionAppRw() throws Exception {
        PostgresTestSupport.migrateAndProvisionAppRw();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.configureDatasourceProperties(registry);
    }

    @Override
    public String getUrl() {
        return String.format("http://localhost:%d/", port);
    }

    @Test
    @DisplayName("Assistant issues clamped, files an edit; owner approves and item updates")
    void inventoryWalkthrough() throws Exception {
        String clinicSlug = seedTestData();

        loginAsRole("assistant", clinicSlug, "password");
        page().navigate(getUrl() + "inventory");
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("الأصناف"))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("صينية التحضير"))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("صرف المخزون"))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("سجل الحركة"))).not().isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("طلبات الموافقة"))).not().isVisible();

        page().navigate(getUrl() + "inventory/issue");
        PlaywrightAssertions.assertThat(page().getByText("كمبوزيت").first()).isVisible();
        page().locator("input[name='requestedQty']").fill("15");
        Response issueResponse = page().waitForResponse(
                candidate -> candidate.url().contains("/inventory/issue"),
                () -> page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("صرف")).first().click());
        org.junit.jupiter.api.Assertions.assertEquals(200, issueResponse.status());
        PlaywrightAssertions.assertThat(page().locator("#toast-root"))
                .containsText("تم صرف الكمية المتاحة فقط: 10");

        page().navigate(getUrl() + "inventory/items");
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("تعديل")).first().click();
        page().locator("input[name='name']").last().fill("كمبوزيت معدّل");
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("إرسال طلب التعديل")).click();
        page().waitForURL(url -> url.contains("/inventory/items"));
        org.junit.jupiter.api.Assertions.assertEquals("بانتظار موافقة المدير", page().locator("body").getAttribute("data-toast"));
        PlaywrightAssertions.assertThat(page().getByText("كمبوزيت", new Page.GetByTextOptions().setExact(true)).first()).isVisible();

        page().context().clearCookies();
        loginAsRole("owner", clinicSlug, "password");
        page().navigate(getUrl() + "inventory/approvals");
        PlaywrightAssertions.assertThat(page().getByText("كمبوزيت", new Page.GetByTextOptions().setExact(true)).first()).isVisible();
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("اعتماد")).first().click();
        page().waitForURL(url -> url.contains("/inventory/approvals"));
        PlaywrightAssertions.assertThat(page().getByText("لا توجد طلبات بانتظار اعتمادك")).isVisible();

        page().navigate(getUrl() + "inventory/ledger");
        PlaywrightAssertions.assertThat(page().getByText("كمبوزيت معدّل", new Page.GetByTextOptions().setExact(true)).first()).isVisible();

        page().navigate(getUrl() + "inventory/items");
        PlaywrightAssertions.assertThat(page().getByText("كمبوزيت معدّل").first()).isVisible();
    }

    @Test
    @DisplayName("Receptionist accesses purchasing areas but not tray or issue")
    void receptionistInventoryNavigation() throws Exception {
        String clinicSlug = seedTestData();

        loginAsRole("receptionist", clinicSlug, "password");
        page().navigate(getUrl() + "inventory");
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("النواقص والطلب"))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(Pattern.compile("الاستلام$")))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("المرتجعات"))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("الموردين"))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("صينية التحضير"))).not().isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("صرف المخزون"))).not().isVisible();

        assertRenders("inventory/orders");
        assertRenders("inventory/receive");
        assertRenders("inventory/returns");
        assertRenders("inventory/suppliers");
        assertRedirectsTo("inventory/tray", "inventory");
        assertRedirectsTo("inventory/issue", "inventory");
    }

    @Test
    @DisplayName("Manager accesses inventory approvals and analytics")
    void managerInventoryNavigation() throws Exception {
        String clinicSlug = seedTestData();

        loginAsRole("manager", clinicSlug, "password");
        page().navigate(getUrl() + "inventory");
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("طلبات الموافقة"))).isVisible();
        PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("تحليل الاستهلاك"))).isVisible();

        assertRenders("inventory/approvals");
        assertRenders("inventory/analytics");
    }

    private void assertRenders(String path) {
        page().navigate(getUrl() + path);
        assertEquals(getUrl() + path, page().url(), path + " must render for this role");
        PlaywrightAssertions.assertThat(page().locator("main h1")).isVisible();
    }

    private void assertRedirectsTo(String path, String landing) {
        page().navigate(getUrl() + path);
        assertEquals(getUrl() + landing, page().url(), path + " must be denied for this role");
    }

    private void loginAsRole(String username, String clinicCode, String password) {
        page().navigate(getUrl() + "login");
        page().getByLabel("كود العيادة").fill(clinicCode);
        page().getByLabel("اسم المستخدم").fill(username);
        page().getByLabel("كلمة المرور").fill(password);
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("دخول")).click();
        page().locator(".clinicos-nav-item").first().waitFor();
    }

    private String seedTestData() throws Exception {
        String clinicSlug = "test-clinic-" + UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic", clinicSlug);

            UUID ownerId = TestFixtures.insertUser(connection, clinicId, "owner", passwordEncoder.encode("password"), "active");
            UUID assistantId = TestFixtures.insertUser(connection, clinicId, "assistant", passwordEncoder.encode("password"), "active");
            UUID managerId = TestFixtures.insertUser(connection, clinicId, "manager", passwordEncoder.encode("password"), "active");
            UUID receptionistId = TestFixtures.insertUser(connection, clinicId, "receptionist", passwordEncoder.encode("password"), "active");
            UUID ownerMembershipId = TestFixtures.insertMembership(connection, clinicId, ownerId, "owner");
            UUID assistantMembershipId = TestFixtures.insertMembership(connection, clinicId, assistantId, "assistant");
            UUID managerMembershipId = TestFixtures.insertMembership(connection, clinicId, managerId, "manager");
            UUID receptionistMembershipId = TestFixtures.insertMembership(connection, clinicId, receptionistId, "receptionist");

            UUID ownerEmpId = TestFixtures.insertEmployee(connection, clinicId, "owner");
            UUID assistantEmpId = TestFixtures.insertEmployee(connection, clinicId, "assistant");
            UUID managerEmpId = TestFixtures.insertEmployee(connection, clinicId, "manager");
            UUID receptionistEmpId = TestFixtures.insertEmployee(connection, clinicId, "receptionist");
            TestFixtures.linkMembershipToEmployee(connection, ownerMembershipId, ownerEmpId);
            TestFixtures.linkMembershipToEmployee(connection, assistantMembershipId, assistantEmpId);
            TestFixtures.linkMembershipToEmployee(connection, managerMembershipId, managerEmpId);
            TestFixtures.linkMembershipToEmployee(connection, receptionistMembershipId, receptionistEmpId);

            TestFixtures.seedRolePermissionDefaults(connection, clinicId);

            DSLContext superDsl = DSL.using(connection, SQLDialect.POSTGRES);
            UUID itemId = UUID.randomUUID();
            superDsl.insertInto(INVENTORY_ITEM, INVENTORY_ITEM.ID, INVENTORY_ITEM.CLINIC_ID,
                    INVENTORY_ITEM.NAME, INVENTORY_ITEM.UOM, INVENTORY_ITEM.UNIT_COST,
                    INVENTORY_ITEM.STORE_ALERT, INVENTORY_ITEM.TRAY_ALERT)
                    .values(itemId, clinicId, "كمبوزيت", "عبوة", new BigDecimal("45.50"), 5, 3)
                    .execute();
            superDsl.insertInto(STOCK_MOVEMENT, STOCK_MOVEMENT.ID, STOCK_MOVEMENT.CLINIC_ID,
                    STOCK_MOVEMENT.ITEM_ID, STOCK_MOVEMENT.LOCATION, STOCK_MOVEMENT.QTY_DELTA,
                    STOCK_MOVEMENT.REASON)
                    .values(UUID.randomUUID(), clinicId, itemId, LocationKind.store,
                            new BigDecimal("10"), MovementReason.receipt)
                    .execute();
        }
        return clinicSlug;
    }
}
