package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.clinicos.shared.TenantContext;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

class TenantSessionBinderTest {

    private final TenantSessionBinder binder = new TenantSessionBinder();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void bindsTenantContextFromSelectedClinicInSession() throws Exception {
        UUID clinicId = UUID.randomUUID();
        RequestHandler handler = registeredHandler();
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID)).thenReturn(clinicId);

        handler.handleRequest(session, mock(VaadinRequest.class), mock(VaadinResponse.class));

        assertThat(TenantContext.get()).contains(clinicId);
    }

    @Test
    void clearsTenantContextWhenNoClinicSelected() throws Exception {
        TenantContext.set(UUID.randomUUID());
        RequestHandler handler = registeredHandler();
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID)).thenReturn(null);

        handler.handleRequest(session, mock(VaadinRequest.class), mock(VaadinResponse.class));

        assertThat(TenantContext.get()).isEmpty();
    }

    private RequestHandler registeredHandler() {
        ServiceInitEvent event = new ServiceInitEvent(mock(VaadinService.class));
        binder.serviceInit(event);
        return event.getAddedRequestHandlers().findFirst().orElseThrow();
    }
}
