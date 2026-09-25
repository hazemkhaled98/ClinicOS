package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import com.clinicos.identity.api.SessionKeys;

class LayoutModelTest {

    private final LayoutModel layoutModel = new LayoutModel();

    @Test
    void arabicLongDateFormatsDayNameNumberAndMonth() {
        LocalDate date = LocalDate.of(2026, 9, 6);

        assertThat(LayoutModel.arabicLongDate(date)).isEqualTo("الأحد 6 سبتمبر");
    }

    @Test
    void roleDisplayNameMapsKnownRolesAndDefaultsOthers() {
        assertThat(LayoutModel.roleDisplayName("owner")).isEqualTo("المالك");
        assertThat(LayoutModel.roleDisplayName("manager")).isEqualTo("مدير");
        assertThat(LayoutModel.roleDisplayName("assistant")).isEqualTo("مساعد");
        assertThat(LayoutModel.roleDisplayName("receptionist")).isEqualTo("موظف استقبال");
        assertThat(LayoutModel.roleDisplayName(null)).isEqualTo("مستخدم");
    }

    @Test
    void forRequestWithNullSessionYieldsEmptyNav() {
        assertThat(layoutModel.forRequest(null, null).nav()).isEmpty();
    }

    @Test
    void forRequestWithMissingRoleAndPermissionsYieldsEmptyNav() {
        MockHttpSession session = new MockHttpSession();

        assertThat(layoutModel.forRequest(session, null).nav()).isEmpty();
    }

    @Test
    void forRequestWithClinicNameReturnsIt() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.CLINIC_NAME, "Al-Noor Clinic");

        assertThat(layoutModel.forRequest(session, null).clinicName()).isEqualTo("Al-Noor Clinic");
    }

    @Test
    void forRequestWithoutClinicNameReturnsDefault() {
        MockHttpSession session = new MockHttpSession();

        assertThat(layoutModel.forRequest(session, null).clinicName()).isEqualTo("عيادتي");
    }

    @Test
    void forRequestWithBlankClinicNameReturnsDefault() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionKeys.CLINIC_NAME, "   ");

        assertThat(layoutModel.forRequest(session, null).clinicName()).isEqualTo("عيادتي");
    }
}
