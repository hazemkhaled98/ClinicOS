package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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

    @Test
    void eachAnalyticsRouteCallsItsServiceAndRenders() {
        when(analytics.profit(CLINIC, null, null)).thenReturn(List.of());
        assertThat(controller.profit(null, null, session("profit"), new ExtendedModelMap())).isEqualTo("inventory-profit");
        verify(analytics).profit(CLINIC, null, null);

        when(analytics.consumption(CLINIC, null, null)).thenReturn(List.of());
        assertThat(controller.analytics(null, null, session("analytics"), new ExtendedModelMap())).isEqualTo("inventory-analytics");
        verify(analytics).consumption(CLINIC, null, null);

        when(analytics.waste(CLINIC, null, null)).thenReturn(List.of());
        assertThat(controller.waste(null, null, session("waste"), new ExtendedModelMap())).isEqualTo("inventory-waste");
        verify(analytics).waste(CLINIC, null, null);

        when(analytics.doctors(CLINIC, null, null)).thenReturn(List.of());
        assertThat(controller.doctors(null, null, session("doctors"), new ExtendedModelMap())).isEqualTo("inventory-doctors");
        verify(analytics).doctors(CLINIC, null, null);

        when(analytics.suppliers(CLINIC, null, null)).thenReturn(List.of());
        assertThat(controller.suppliers(null, null, session("supAnalysis"), new ExtendedModelMap())).isEqualTo("inventory-supplier-analytics");
        verify(analytics).suppliers(CLINIC, null, null);

        when(analytics.itemPrices(CLINIC, null, null)).thenReturn(List.of());
        assertThat(controller.items(null, null, session("itemAnalysis"), new ExtendedModelMap())).isEqualTo("inventory-item-analysis");
        verify(analytics).itemPrices(CLINIC, null, null);
    }

    @Test
    void routesPassParsedDatesThroughToTheService() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        when(analytics.profit(CLINIC, from, to)).thenReturn(List.of());

        assertThat(controller.profit(from, to, session("profit"), new ExtendedModelMap())).isEqualTo("inventory-profit");
        verify(analytics).profit(CLINIC, from, to);
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
