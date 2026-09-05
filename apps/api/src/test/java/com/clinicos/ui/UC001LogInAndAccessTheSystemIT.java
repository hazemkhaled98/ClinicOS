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
import org.vaadin.addons.dramafinder.AbstractBasePlaywrightIT;
import org.vaadin.addons.dramafinder.element.ButtonElement;
import org.vaadin.addons.dramafinder.element.PasswordFieldElement;
import org.vaadin.addons.dramafinder.element.TextFieldElement;

import com.clinicos.PostgresTestSupport;
import com.clinicos.TestFixtures;

/**
 * UC-001: Log In and Access the System, exercised end-to-end through a real
 * browser against the running application (blackbox -- no service/DSLContext
 * access, no Karibu mock).
 *
 * <p>{@link LoginView} submits natively to {@code /login}
 * ({@code LoginForm.setAction("login")}), which Spring Security's form-login
 * processes. These tests drive that real POST: the success scenario only
 * leaves the login screen when a {@code SPRING_SECURITY_CONTEXT} exists, so
 * reaching a non-login page proves the security context was initialized.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC001LogInAndAccessTheSystemIT extends AbstractBasePlaywrightIT {

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

    @Override
    public String getView() {
        return "login";
    }

    private String rootUrl() {
        return String.format("http://localhost:%d/", port);
    }

    private void login(String username, String password) {
        TextFieldElement.getByLabel(page, "اسم المستخدم").setValue(username);
        PasswordFieldElement.getByLabel(page, "كلمة المرور").setValue(password);
        ButtonElement.getByText(page, "دخول").click();
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void seedUserWithClinic(String username, String rawPassword) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(
                    connection, "Test Clinic " + username, "clinic-" + username);
            UUID userId = TestFixtures.insertUser(
                    connection, username, passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicId, userId);
        }
    }

    @Nested
    @DisplayName("Step 1: Login screen")
    class LoginScreen {

        @Test
        @DisplayName("Shows username, password and submit button")
        void showsLoginForm() {
            TextFieldElement.getByLabel(page, "اسم المستخدم").assertVisible();
            PasswordFieldElement.getByLabel(page, "كلمة المرور").assertVisible();
            ButtonElement.getByText(page, "دخول").assertVisible();
        }
    }

    @Nested
    @DisplayName("Step 2: Authentication via POST /login")
    class Authentication {

        @Test
        @DisplayName("Invalid credentials redirect to /login?error and keep the user out")
        void failedLoginStaysOut() throws Exception {
            String username = "badlogin-" + uniqueSuffix();
            seedUserWithClinic(username, "the-real-password");

            login(username, "wrong-password");

            page.waitForURL(url -> url.contains("/login?error"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                    page.getByText("اسم المستخدم أو كلمة المرور غير صحيحة")).isVisible();

            page.navigate(rootUrl());
            page.waitForURL(url -> url.contains("/login"));
            assertThat(page.url()).contains("/login");
        }

        @Test
        @DisplayName("Valid credentials submit to /login and initialize the security context")
        void successfulLoginInitializesSecurityContext() throws Exception {
            String username = "sessionuser-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            seedUserWithClinic(username, rawPassword);

            login(username, rawPassword);

            page.waitForURL(url -> !url.contains("/login"));
            assertThat(page.url()).doesNotContain("/login");
        }

        private void login(String username, String password) {
            TextFieldElement.getByLabel(page, "اسم المستخدم").setValue(username);
            PasswordFieldElement.getByLabel(page, "كلمة المرور").setValue(password);
            ButtonElement.getByText(page, "دخول").click();
        }

        private String uniqueSuffix() {
            return UUID.randomUUID().toString().substring(0, 8);
        }

        private void seedUserWithClinic(String username, String rawPassword) throws Exception {
            try (Connection connection = DriverManager.getConnection(
                    PostgresTestSupport.POSTGRES.getJdbcUrl(),
                    PostgresTestSupport.POSTGRES.getUsername(),
                    PostgresTestSupport.POSTGRES.getPassword())) {
                UUID clinicId = TestFixtures.insertClinic(
                        connection, "Test Clinic " + username, "clinic-" + username);
                UUID userId = TestFixtures.insertUser(
                        connection, username, passwordEncoder.encode(rawPassword), "active");
                TestFixtures.insertMembership(connection, clinicId, userId);
            }
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

            page.navigate(rootUrl() + "signup");
            page.waitForURL(url -> url.contains("/signup"));

            TextFieldElement.getByLabel(page, "اسم العيادة").setValue("عيادة الاختبار " + username);
            TextFieldElement.getByLabel(page, "الاسم الكامل").setValue("المالك الجديد");
            TextFieldElement.getByLabel(page, "اسم المستخدم").setValue(username);
            PasswordFieldElement.getByLabel(page, "كلمة المرور").setValue(rawPassword);
            PasswordFieldElement.getByLabel(page, "تأكيد كلمة المرور").setValue(rawPassword);

            ButtonElement.getByText(page, "إنشاء العيادة").click();

            page.waitForURL(url -> url.contains("/login"));
            assertThat(page.url()).contains("signup=success");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                    page.getByText("تم إنشاء العيادة بنجاح، سجّل الدخول لبدء العمل")).isVisible();

            login(username, rawPassword);

            page.waitForURL(url -> !url.contains("/login"));
            assertThat(page.url()).doesNotContain("/login");
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
            seedUserWithClinic(username, rawPassword);

            login(username, rawPassword);
            page.waitForURL(url -> !url.contains("/login"));

            page.locator(".clinicos-logout").click();

            page.waitForURL(url -> url.contains("/login"));
            assertThat(page.url()).contains("/login");

            page.navigate(rootUrl());
            page.waitForURL(url -> url.contains("/login"));
            assertThat(page.url()).contains("/login");

            assertThat(page.context().cookies().stream()
                    .noneMatch(cookie -> "lastSection".equals(cookie.name))).isTrue();
        }
    }
}