package com.clinicos.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;

@Route("")
public class HelloView extends H1 {

    public HelloView() {
        super("ClinicOS");

        VaadinSession session = VaadinSession.getCurrent();
        if (session == null || session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID) == null) {
            UI.getCurrent().navigate(ClinicPickerView.class);
        }
    }
}
