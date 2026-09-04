package com.clinicos.ui;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.clinicos.shared.TenantContext;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * Rebinds {@link TenantContext} from the session's selected clinic
 * (written by {@link ClinicPickerView}) at the start of every Vaadin
 * request, so business queries reached by navigating back into an
 * existing session -- not just the request that ran clinic selection
 * itself -- have a tenant bound before {@code TenantConnectionListener}
 * checks for one. Registered as a {@link com.vaadin.flow.server.RequestHandler}
 * rather than a navigation listener so it also covers non-navigating
 * server round trips (e.g. an in-place server call on the current view),
 * not only page loads.
 */
@Component
public class TenantSessionBinder implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.addRequestHandler((session, request, response) -> {
            // VaadinSession.getAttribute asserts the session lock is held --
            // RequestHandler.handleRequest does not hold it by default.
            session.lock();
            try {
                Object clinicId = session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID);
                if (clinicId instanceof UUID id) {
                    TenantContext.set(id);
                } else {
                    TenantContext.clear();
                }
            } finally {
                session.unlock();
            }
            return false;
        });
    }
}
