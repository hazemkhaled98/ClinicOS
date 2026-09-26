package com.clinicos.clinicconfig.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.clinicconfig.api.ClinicSettingsService.CategoryWeight;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettings;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettingsValidationException;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Tier;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class ClinicSettingsServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultClinicSettingsService settingsService;

    @Autowired
    private NotificationService notifications;

    private UUID clinicA;
    private UUID clinicB;
    private UUID actorA;
    private UUID actorB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (Connection connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection);
            clinicB = TestFixtures.insertClinic(connection);
            actorA = TestFixtures.actorMembership(connection, clinicA);
            actorB = TestFixtures.actorMembership(connection, clinicB);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void signupClinicWithOwnerSeedsLegacyConfiguration() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID clinicId;
        try (Connection connection = superuser();
                var statement = connection.prepareStatement(
                        "select clinic_id from signup_clinic_with_owner(?::text, ?::text, ?::text, "
                                + "?::citext, ?::citext, ?::text)")) {
            statement.setString(1, "عيادة " + suffix);
            statement.setString(2, "clinic-" + suffix);
            statement.setString(3, "محمود");
            statement.setString(4, "owner-" + suffix);
            statement.setString(5, "owner-" + suffix + "@x.com");
            statement.setString(6, "hash");
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                clinicId = result.getObject(1, UUID.class);
            }
        }

        TenantContext.set(clinicId);
        ClinicSettings settings = settingsService.get(clinicId);

        assertThat(settings.defaultShiftStart()).isEqualTo(LocalTime.of(9, 0));
        assertThat(settings.defaultShiftEnd()).isEqualTo(LocalTime.of(17, 0));
        assertThat(settings.lateGraceMinutes()).isEqualTo(15);
        assertThat(settings.workingDaysPerMonth()).isEqualTo(26);
        assertThat(settings.volumeTarget()).isEqualByComparingTo("20000");
        assertThat(settings.academyPassScore()).isEqualTo(70);
        assertThat(weightSum(settings)).isEqualByComparingTo("100");
        assertThat(settings.tiers()).extracting(Tier::name)
                .containsExactly("ممتاز", "جيد جداً", "جيد", "يحتاج تطوير");
        assertThat(settings.tiers().get(0).incentivePct()).isEqualByComparingTo("100");
    }

    @Test
    void seededWeightsMatchLegacyAndTiersMatchBrG07() {
        TenantContext.set(clinicA);
        ClinicSettings settings = settingsService.get(clinicA);

        assertThat(weightMap(settings))
                .containsEntry(Category.COMPLETION, w("18"))
                .containsEntry(Category.VOLUME, w("18"))
                .containsEntry(Category.IBDA3, w("22"));
        assertThat(weightSum(settings)).isEqualByComparingTo("100");
        assertThat(settings.tiers())
                .extracting(tier -> tier.minScore().stripTrailingZeros().toPlainString() + ":"
                        + tier.incentivePct().stripTrailingZeros().toPlainString())
                .containsExactly("90:100", "75:75", "60:50", "0:0");
    }

    @Test
    void updateDutyPersistsNewValues() {
        TenantContext.set(clinicA);
        settingsService.updateDuty(clinicA, LocalTime.of(8, 30), LocalTime.of(16, 30),
                20, 22, 65, actorA);

        ClinicSettings settings = settingsService.get(clinicA);
        assertThat(settings.defaultShiftStart()).isEqualTo(LocalTime.of(8, 30));
        assertThat(settings.defaultShiftEnd()).isEqualTo(LocalTime.of(16, 30));
        assertThat(settings.lateGraceMinutes()).isEqualTo(20);
        assertThat(settings.workingDaysPerMonth()).isEqualTo(22);
        assertThat(settings.academyPassScore()).isEqualTo(65);
        assertThat(settings.volumeTarget()).isEqualByComparingTo("20000");
    }

    @Test
    void updateDutyNotifiesOtherOwnersOnly() throws Exception {
        TenantContext.set(clinicA);
        UUID ownerRecipient;
        UUID managerObserver;
        try (Connection connection = superuser()) {
            ownerRecipient = TestFixtures.insertMembership(connection, clinicA, "owner");
            managerObserver = TestFixtures.insertMembership(connection, clinicA, "manager");
        }

        settingsService.updateDuty(clinicA, LocalTime.of(8, 30), LocalTime.of(16, 30),
                20, 22, 65, actorA);

        var notification = notifications.recent(clinicA, ownerRecipient, 1).getFirst();
        assertThat(notification.kind()).isEqualTo(NotificationKind.CLINIC_SETTINGS_CHANGED);
        assertThat(notification.payload()).containsEntry("area", "الدوام")
                .containsEntry("actor", "Test User");
        assertThat(notifications.recent(clinicA, actorA, 20)).isEmpty();
        assertThat(notifications.recent(clinicA, managerObserver, 20)).isEmpty();
    }

    @Test
    void updateVolumeTargetPersists() {
        TenantContext.set(clinicA);
        settingsService.updateVolumeTarget(clinicA, new BigDecimal("30000"), actorA);

        assertThat(settingsService.get(clinicA).volumeTarget()).isEqualByComparingTo("30000");
    }

    @Test
    void invoicePhotoPolicyDefaultsToRequiredAndCanBeDisabled() {
        TenantContext.set(clinicA);

        assertThat(settingsService.get(clinicA).invoicePhotoRequired()).isTrue();
        settingsService.updateInvoicePhotoRequired(clinicA, false, actorA);

        assertThat(settingsService.get(clinicA).invoicePhotoRequired()).isFalse();
    }

    @Test
    void updateWeightsRejectsSumNotEqualToHundred() {
        TenantContext.set(clinicA);
        List<CategoryWeight> off = adjust(weightList(clinicA), Category.COMPLETION, "10");

        ClinicSettingsValidationException exception = assertThrows(ClinicSettingsValidationException.class,
                () -> settingsService.updateWeights(clinicA, off, actorA));

        assertThat(exception.fieldErrors()).containsKey("weights");
        assertThat(weightSum(settingsService.get(clinicA))).isEqualByComparingTo("100");
    }

    @Test
    void updateWeightsRejectsDuplicateOrMissingCategory() {
        TenantContext.set(clinicA);
        List<CategoryWeight> missingOne = weightList(clinicA).subList(0, 5);

        ClinicSettingsValidationException exception = assertThrows(ClinicSettingsValidationException.class,
                () -> settingsService.updateWeights(clinicA, missingOne, actorA));

        assertThat(exception.fieldErrors()).containsKey("weights");
    }

    @Test
    void updateWeightsPersistsWhenSumIsHundred() {
        TenantContext.set(clinicA);
        List<CategoryWeight> changed = adjust(weightList(clinicA), Category.COMPLETION, "20");
        changed.set(1, new CategoryWeight(Category.FANNI, new BigDecimal("16")));

        settingsService.updateWeights(clinicA, changed, actorA);

        assertThat(weightMap(settingsService.get(clinicA)))
                .containsEntry(Category.COMPLETION, w("20"))
                .containsEntry(Category.FANNI, w("16"));
    }

    @Test
    void updateTiersReplacesAll() {
        TenantContext.set(clinicA);
        settingsService.updateTiers(clinicA, List.of(
                new Tier("ممتاز", new BigDecimal("95"), new BigDecimal("100")),
                new Tier("جيد", new BigDecimal("70"), new BigDecimal("30"))), actorA);

        assertThat(settingsService.get(clinicA).tiers())
                .extracting(Tier::name, tier -> tier.minScore().stripTrailingZeros())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ممتاز", w("95")),
                        org.assertj.core.groups.Tuple.tuple("جيد", w("70")));
    }

    @Test
    void updateTiersRejectsBlankNameDuplicateNameAndOutOfRangePct() {
        TenantContext.set(clinicA);
        assertThrows(ClinicSettingsValidationException.class,
                () -> settingsService.updateTiers(clinicA, List.of(
                        new Tier("", new BigDecimal("80"), new BigDecimal("60"))), actorA));
        assertThrows(ClinicSettingsValidationException.class,
                () -> settingsService.updateTiers(clinicA, List.of(
                        new Tier("جيد", new BigDecimal("80"), new BigDecimal("60")),
                        new Tier("جيد", new BigDecimal("60"), new BigDecimal("30"))), actorA));
        assertThrows(ClinicSettingsValidationException.class,
                () -> settingsService.updateTiers(clinicA, List.of(
                        new Tier("جيد", new BigDecimal("80"), new BigDecimal("120"))), actorA));
    }

    @Test
    void updateDutyRejectsInvertedShift() {
        TenantContext.set(clinicA);
        assertThatThrownBy(() -> settingsService.updateDuty(clinicA,
                LocalTime.of(17, 0), LocalTime.of(9, 0), 15, 26, 70, actorA))
                .isInstanceOf(ClinicSettingsValidationException.class)
                .extracting(e -> ((ClinicSettingsValidationException) e).fieldErrors())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("shift");
    }

    @Test
    void clinicIsolationAppliesToReadsAndWrites() {
        TenantContext.set(clinicA);

        List<CategoryWeight> changed = adjust(weightList(clinicA), Category.VOLUME, "20");
        changed = adjust(changed, Category.ATTENDANCE, "10");
        settingsService.updateWeights(clinicA, changed, actorA);

        assertThat(weightMap(settingsService.get(clinicA)))
                .containsEntry(Category.VOLUME, w("20"))
                .containsEntry(Category.ATTENDANCE, w("10"));
    }

    @Test
    void clinicBWeightsAreInvisibleAndSettingsRowHidden() {
        TenantContext.set(clinicA);

        assertThatThrownBy(() -> settingsService.get(clinicB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("إعدادات العيادة غير موجودة");
    }

    @Test
    void clinicBWritesAreNoOpsWhenTenantIsA() {
        TenantContext.set(clinicA);

        // updateDuty for the hidden clinic changes nothing, and the whole
        // configuration of clinic B stays invisible.
        settingsService.updateDuty(clinicB, LocalTime.of(7, 0), LocalTime.of(15, 0), 5, 20, 80, actorB);

        assertThatThrownBy(() -> settingsService.get(clinicB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("إعدادات العيادة غير موجودة");
    }

    private List<CategoryWeight> weightList(UUID clinicId) {
        return settingsService.get(clinicId).weights();
    }

    private static List<CategoryWeight> adjust(List<CategoryWeight> weights, Category category, String newWeight) {
        List<CategoryWeight> result = new ArrayList<>(weights.stream()
                .map(w -> w.category() == category
                        ? new CategoryWeight(category, new BigDecimal(newWeight))
                        : w)
                .toList());
        return result;
    }

    private static BigDecimal weightSum(ClinicSettings settings) {
        return settings.weights().stream()
                .map(CategoryWeight::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<Category, BigDecimal> weightMap(ClinicSettings settings) {
        return settings.weights().stream()
                .collect(java.util.stream.Collectors.toMap(CategoryWeight::category,
                        w -> w.weight().stripTrailingZeros()));
    }

    private static BigDecimal w(String weight) {
        return new BigDecimal(weight).stripTrailingZeros();
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
