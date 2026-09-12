package com.clinicos.clinicconfig.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.clinicconfig.api.GamificationService.BadgeThreshold;
import com.clinicos.clinicconfig.api.GamificationService.GamificationSettings;
import com.clinicos.clinicconfig.api.GamificationService.WeeklyGoal;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class DefaultGamificationServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultGamificationService gamificationService;

    private UUID clinicA;
    private UUID clinicB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void getReturnsDefaultsWhenNoSettings() {
        TenantContext.set(clinicA);

        GamificationSettings settings = gamificationService.get(clinicA);

        assertThat(settings.showLevelRing()).isTrue();
        assertThat(settings.showLeaderboard()).isFalse();
    }

    @Test
    void updateThenGetReturnsSaved() {
        TenantContext.set(clinicA);
        gamificationService.updateSettings(clinicA,
                new GamificationSettings(false, false, false, false, true, false));

        GamificationSettings settings = gamificationService.get(clinicA);

        assertThat(settings.showLevelRing()).isFalse();
        assertThat(settings.showLeaderboard()).isTrue();
    }

    @Test
    void settingsArePerClinic() {
        TenantContext.set(clinicA);
        gamificationService.updateSettings(clinicA,
                new GamificationSettings(true, false, false, false, false, false));
        TenantContext.set(clinicB);
        gamificationService.updateSettings(clinicB,
                new GamificationSettings(false, true, false, false, false, false));

        TenantContext.set(clinicA);
        GamificationSettings a = gamificationService.get(clinicA);
        TenantContext.set(clinicB);
        GamificationSettings b = gamificationService.get(clinicB);

        assertThat(a.showLevelRing()).isTrue();
        assertThat(a.showStreaks()).isFalse();
        assertThat(b.showLevelRing()).isFalse();
        assertThat(b.showStreaks()).isTrue();
    }

    @Test
    void updateGoalThenList() {
        TenantContext.set(clinicA);
        gamificationService.updateGoal(clinicA, 1, "مهارة التبسم", 10);
        gamificationService.updateGoal(clinicA, 2, "ال干净", 20);

        List<WeeklyGoal> goals = gamificationService.getGoals(clinicA);

        assertThat(goals).hasSize(2);
        assertThat(goals.get(0).slot()).isEqualTo(1);
        assertThat(goals.get(0).title()).isEqualTo("مهارة التبسم");
        assertThat(goals.get(0).target()).isEqualTo(10);
    }

    @Test
    void updateGoalUpserts() {
        TenantContext.set(clinicA);
        gamificationService.updateGoal(clinicA, 1, "أول", 5);
        gamificationService.updateGoal(clinicA, 1, "جديد", 15);

        List<WeeklyGoal> goals = gamificationService.getGoals(clinicA);

        assertThat(goals).hasSize(1);
        assertThat(goals.get(0).title()).isEqualTo("جديد");
        assertThat(goals.get(0).target()).isEqualTo(15);
    }

    @Test
    void updateThresholdThenList() {
        TenantContext.set(clinicA);
        gamificationService.updateThreshold(clinicA, "المهام اليومية", 10);
        gamificationService.updateThreshold(clinicA, "الإنجازات", 20);

        List<BadgeThreshold> thresholds = gamificationService.getThresholds(clinicA);

        assertThat(thresholds).hasSize(2);
        assertThat(thresholds.get(0).name()).isEqualTo("الإنجازات");
        assertThat(thresholds.get(0).threshold()).isEqualTo(20);
    }

    @Test
    void updateThresholdUpserts() {
        TenantContext.set(clinicA);
        gamificationService.updateThreshold(clinicA, "شارة", 5);
        gamificationService.updateThreshold(clinicA, "شارة", 25);

        List<BadgeThreshold> thresholds = gamificationService.getThresholds(clinicA);

        assertThat(thresholds).hasSize(1);
        assertThat(thresholds.get(0).threshold()).isEqualTo(25);
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
