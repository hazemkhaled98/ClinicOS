package com.clinicos.ui;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.router.Route;

/**
 * Phase 0 smoke-test route. Replaced by the real login/app shell in
 * Phase 1.
 */
@Route("")
public class HelloView extends H1 {

    public HelloView() {
        super("ClinicOS");
    }
}
