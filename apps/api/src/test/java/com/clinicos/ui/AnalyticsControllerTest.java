package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.inventory.InventoryAnalyticsService;
import com.clinicos.shared.ActivityLogService;

import jakarta.servlet.http.HttpSession;

class AnalyticsControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private AnalyticsController controller;
    private InventoryAnalyticsService analytics;

    @BeforeEach
    void setUp() {
        analytics = mock(InventoryAnalyticsService.class);
        controller = new AnalyticsController(mock(LayoutModel.class), analytics, mock(ActivityLogService.class));
    }

    @Test
    void everyAnalyticsRouteRedirectsWithoutItsPermission() {
        HttpSession session = session();
        assertThat(controller.dashboard(session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
        assertThat(controller.profit(null, null, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
        assertThat(controller.analytics(null, null, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
        assertThat(controller.waste(null, null, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
        assertThat(controller.doctors(null, null, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
        assertThat(controller.suppliers(null, null, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
        assertThat(controller.items(null, null, session, new ExtendedModelMap())).isEqualTo("redirect:/inventory");
    }

    @Test
    void dashboardRendersForPermittedManager() {
        HttpSession session = session("dash");
        when(analytics.dashboard(CLINIC)).thenReturn(new InventoryAnalyticsService.Dashboard(1, 2, 3,
                java.math.BigDecimal.ONE, java.math.BigDecimal.TEN));
        assertThat(controller.dashboard(session, new ExtendedModelMap())).isEqualTo("inventory-dash");
    }

    private HttpSession session(String... codes) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn("manager");
        when(session.getAttribute(SessionKeys.PERMISSIONS)).thenReturn(List.of(codes));
        return session;
    }
}
