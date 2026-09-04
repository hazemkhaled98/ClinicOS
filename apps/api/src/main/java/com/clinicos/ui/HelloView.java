package com.clinicos.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;

@Route(value = "", layout = MainLayout.class)
public class HelloView extends VerticalLayout {

    public HelloView() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null || session.getAttribute(ClinicPickerView.SESSION_CLINIC_ID) == null) {
            UI.getCurrent().navigate(ClinicPickerView.class);
            return;
        }

        H1 heading = new H1("ClinicOS");
        heading.getElement().setAttribute("dir", "rtl");
        add(heading);
    }
}
