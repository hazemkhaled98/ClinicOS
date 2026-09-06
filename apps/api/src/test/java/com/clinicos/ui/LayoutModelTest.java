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
        assertThat(LayoutModel.roleDisplayName("receptionist")).isEqualTo("مستخدم");
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
}
