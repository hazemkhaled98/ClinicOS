package com.clinicos.shared;

import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

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
import com.clinicos.identity.api.MembershipLookupService;
import com.clinicos.identity.api.MembershipLookupService.Membership;
import com.clinicos.identity.internal.CredentialsLookupService;
import com.clinicos.identity.internal.CredentialsLookupService.CredentialsRow;

@SpringBootTest(classes = Application.class)
class AuthModeTenantEscapeIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private CredentialsLookupService credentialsLookupService;

    @Autowired
    private MembershipLookupService membershipLookupService;

    private UUID testUserId;
    private UUID testClinicId;
    private String uniqueSuffix;

    @BeforeEach
    void seedTestData() throws Exception {
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            testUserId = TestFixtures.insertUserWithEmail(connection, "testuser-" + uniqueSuffix, "test-" + uniqueSuffix + "@example.com", "hashed-password-123", "Test User");
            testClinicId = TestFixtures.insertClinic(connection, "Test Clinic " + uniqueSuffix, "test-clinic-" + uniqueSuffix);
            TestFixtures.insertMembership(connection, testClinicId, testUserId, "owner");
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void credentialsLookupByUsernameSucceedsInAuthMode() {
        TenantContext.enterAuthMode();

        CredentialsRow credentials = credentialsLookupService.credentialsLookupByUsername("testuser-" + uniqueSuffix);

        assertThat(credentials.passwordHash()).isEqualTo("hashed-password-123");
    }

    @Test
    void credentialsLookupByUsernameReturnsUserIdAndStatus() {
        TenantContext.enterAuthMode();

        CredentialsRow credentials = credentialsLookupService.credentialsLookupByUsername("testuser-" + uniqueSuffix);

        assertThat(credentials).isNotNull();
        assertThat(credentials.id()).isEqualTo(testUserId);
        assertThat(credentials.passwordHash()).isEqualTo("hashed-password-123");
        assertThat(credentials.status()).isEqualTo("active");
    }

    @Test
    void membershipLookupReturnsAllActiveClinicMemberships() {
        TenantContext.enterAuthMode();

        List<Membership> memberships = membershipLookupService.findByUserId(testUserId);

        assertThat(memberships).hasSize(1);
        Membership membership = memberships.get(0);
        assertThat(membership.clinicId()).isEqualTo(testClinicId);
        assertThat(membership.clinicName()).isEqualTo("Test Clinic " + uniqueSuffix);
        assertThat(membership.roleCode()).isEqualTo("owner");
    }

    @Test
    void rLSScopedTableReturnsZeroRowsInAuthMode() {
        TenantContext.enterAuthMode();

        long count = transactionTemplate.execute(status -> {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            return DSL.using(connection, SQLDialect.POSTGRES).fetchCount(CLINIC_SETTINGS);
        });

        assertThat(count).isEqualTo(0);
    }

    @Test
    void exitAuthModeRestoresNormalBehavior() {
        TenantContext.enterAuthMode();
        TenantContext.exitAuthMode();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            DSL.using(connection, SQLDialect.POSTGRES).fetchCount(CLINIC_SETTINGS);
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound");
    }

}
