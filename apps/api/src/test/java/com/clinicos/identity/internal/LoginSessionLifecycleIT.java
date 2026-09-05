package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.shared.TenantContext;
import jakarta.servlet.http.HttpSession;

/**
 * Covers UC-001 step 5 (session started on successful login), step 8 (session
 * ended on logout), and the failure postcondition (no session on bad
 * credentials) at the Spring Security level -- the layer Karibu cannot drive
 * since it does not perform a real form-login POST.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class LoginSessionLifecycleIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private String uniqueSuffix;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void successfulLoginStartsASession() throws Exception {
        String username = "sessionuser-" + uniqueSuffix;
        String rawPassword = "correct-horse-battery-staple";
        String clinicSlug = insertActiveUserWithClinic(username, rawPassword);

        MvcResult result = mockMvc.perform(post("/login")
                .param("username", username)
                .param("password", rawPassword)
                .param("clinic", clinicSlug)
                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();
    }

    @Test
    void failedLoginStartsNoSessionAndStaysOnLoginScreen() throws Exception {
        String username = "badlogin-" + uniqueSuffix;
        String clinicSlug = insertActiveUserWithClinic(username, "the-real-password");

        MvcResult result = mockMvc.perform(post("/login")
                .param("username", username)
                .param("password", "wrong-password")
                .param("clinic", clinicSlug)
                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login*"))
                .andReturn();

        HttpSession session = result.getRequest().getSession(false);
        if (session != null) {
            assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
        }
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String username = "logoutuser-" + uniqueSuffix;
        String rawPassword = "correct-horse-battery-staple";
        String clinicSlug = insertActiveUserWithClinic(username, rawPassword);

        MvcResult loginResult = mockMvc.perform(post("/login")
                .param("username", username)
                .param("password", rawPassword)
                .param("clinic", clinicSlug)
                .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(session.isInvalid()).isTrue();
    }

    private String insertActiveUserWithClinic(String username, String rawPassword) throws Exception {
        String slug = "test-clinic-" + uniqueSuffix;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic " + uniqueSuffix, slug);
            UUID userId = TestFixtures.insertUser(connection, clinicId, username,
                    passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicId, userId);
        }
        return slug;
    }
}
