package com.clinicos.ui.nav;

import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * A drawer entry with its navigation route and Arabic copy.
 */
public record NavSection(String key, String route, String label, String title, String subtitle, VaadinIcon icon) {
}