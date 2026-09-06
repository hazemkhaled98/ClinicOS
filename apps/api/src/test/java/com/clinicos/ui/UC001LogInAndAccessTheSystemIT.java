package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * UC-001: Log In and Access the System, exercised end-to-end through a real
 * browser against the running application (blackbox -- no service/DSLContext
 * access). The Thymeleaf login form submits natively to {@code /login}, which
 * Spring Security's form-login processes; leaving the login screen proves the
 * security context was initialized.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC001LogInAndAccessTheSystemIT extends AbstractBrowserIT {

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

    private void assertLandedInApp() {
        page().locator(".clinicos-nav-item").first().waitFor();
        assertThat(page().url()).doesNotContain("/login");
    }

    private void login(String username, String password, String clinicCode) {
        page().getByLabel("كود العيادة").fill(clinicCode);
        page().getByLabel("اسم المستخدم").fill(username);
        page().getByLabel("كلمة المرور").fill(password);
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("دخول")).click();
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String seedUserWithClinic(String username, String rawPassword) throws Exception {
        String slug = "clinic-" + uniqueSuffix();
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(
                    connection, "Test Clinic " + username, slug);
            UUID userId = TestFixtures.insertUser(
                    connection, clinicId, username, passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicId, userId);
        }
        return slug;
    }

    @Nested
    @DisplayName("Step 1: Login screen")
    class LoginScreen {

        @Test
        @DisplayName("Shows username, password and submit button")
        void showsLoginForm() {
            page().navigate(getUrl() + "login");
            page().waitForURL(url -> url.contains("/login"));
            PlaywrightAssertions.assertThat(page().getByLabel("كود العيادة")).isVisible();
            PlaywrightAssertions.assertThat(page().getByLabel("اسم المستخدم")).isVisible();
            PlaywrightAssertions.assertThat(page().getByLabel("كلمة المرور")).isVisible();
            PlaywrightAssertions.assertThat(page().getByRole(
                    AriaRole.BUTTON, new Page.GetByRoleOptions().setName("دخول"))).isVisible();
        }
    }

    @Nested
    @DisplayName("Step 2: Authentication via POST /login")
    class Authentication {

        @Test
        @DisplayName("Invalid credentials redirect to /login?error and keep the user out")
        void failedLoginStaysOut() throws Exception {
            String username = "badlogin-" + uniqueSuffix();
            String clinicSlug = seedUserWithClinic(username, "the-real-password");

            page().navigate(getUrl() + "login");
            login(username, "wrong-password", clinicSlug);

            page().waitForURL(url -> url.contains("/login?error"));
            PlaywrightAssertions.assertThat(
                    page().getByText("بيانات الدخول غير صحيحة")).isVisible();

            page().navigate(getUrl());
            page().waitForURL(url -> url.contains("/login"));
            assertThat(page().url()).contains("/login");
        }

        @Test
        @DisplayName("Valid credentials submit to /login and initialize the security context")
        void successfulLoginInitializesSecurityContext() throws Exception {
            String username = "sessionuser-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            String clinicSlug = seedUserWithClinic(username, rawPassword);

            page().navigate(getUrl() + "login");
            login(username, rawPassword, clinicSlug);

            assertLandedInApp();
        }

        @Test
        @DisplayName("The same username in two clinics logs in with either clinic code")
        void sameUsernameAcrossClinicsLogsInWithOwnClinicCode() throws Exception {
            String username = "shared-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            String slugA = "shared-clinic-a-" + uniqueSuffix();
            String slugB = "shared-clinic-b-" + uniqueSuffix();
            try (Connection connection = DriverManager.getConnection(
                    PostgresTestSupport.POSTGRES.getJdbcUrl(),
                    PostgresTestSupport.POSTGRES.getUsername(),
                    PostgresTestSupport.POSTGRES.getPassword())) {
                UUID clinicA = TestFixtures.insertClinic(connection, "Shared A " + uniqueSuffix(), slugA);
                UUID userA = TestFixtures.insertUser(connection, clinicA, username,
                        passwordEncoder.encode(rawPassword), "active");
                TestFixtures.insertMembership(connection, clinicA, userA);
                UUID clinicB = TestFixtures.insertClinic(connection, "Shared B " + uniqueSuffix(), slugB);
                UUID userB = TestFixtures.insertUser(connection, clinicB, username,
                        passwordEncoder.encode(rawPassword), "active");
                TestFixtures.insertMembership(connection, clinicB, userB);
            }

            page().navigate(getUrl() + "login");
            login(username, rawPassword, slugA);

            assertLandedInApp();

            page().locator(".clinicos-topbar-menu").click();
            page().locator(".clinicos-logout").click();
            page().waitForURL(url -> url.contains("/login"));

            login(username, rawPassword, slugB);

            assertLandedInApp();
        }
    }

    @Nested
    @DisplayName("Step 3: Self-service sign-up")
    class SignUp {

        @Test
        @DisplayName("Creating a clinic lands on login with a success banner, then the new owner logs into the app")
        void signUpThenLogIn() {
            String username = "newowner-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";

            page().navigate(getUrl() + "signup");
            page().waitForURL(url -> url.contains("/signup"));

            page().getByLabel("اسم العيادة").fill("عيادة الاختبار " + username);
            page().getByLabel("الاسم الكامل").fill("المالك الجديد");
            page().getByLabel("اسم المستخدم").fill(username);
            page().getByLabel("كلمة المرور", new Page.GetByLabelOptions().setExact(true)).fill(rawPassword);
            page().getByLabel("تأكيد كلمة المرور").fill(rawPassword);

            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("إنشاء العيادة")).click();

            page().waitForURL(url -> url.contains("/login"));
            assertThat(page().url()).contains("signup=success");
            assertThat(page().url()).contains("clinic=");
            PlaywrightAssertions.assertThat(
                    page().getByText("تم إنشاء العيادة بنجاح، سجّل الدخول لبدء العمل")).isVisible();

            page().locator("#clinic").waitFor();
            String prefilledClinicCode = page().locator("#clinic").inputValue();
            assertThat(prefilledClinicCode).isNotBlank();

            login(username, rawPassword, prefilledClinicCode);

            assertLandedInApp();
        }
    }

    @Nested
    @DisplayName("Step 4: Logout")
    class Logout {

        @Test
        @DisplayName("Clicking the drawer logout control ends the session and returns to /login")
        void logoutEndsSessionAndClearsCookie() throws Exception {
            String username = "logoutuser-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            String clinicSlug = seedUserWithClinic(username, rawPassword);

            page().navigate(getUrl() + "login");
            login(username, rawPassword, clinicSlug);
            assertLandedInApp();

            page().locator(".clinicos-topbar-menu").click();
            page().locator(".clinicos-logout").click();

            page().waitForURL(url -> url.contains("/login"));
            assertThat(page().url()).contains("/login");

            page().navigate(getUrl());
            page().waitForURL(url -> url.contains("/login"));
            assertThat(page().url()).contains("/login");

            assertThat(page().context().cookies().stream()
                    .noneMatch(cookie -> "lastSection".equals(cookie.name))).isTrue();
        }
    }
}