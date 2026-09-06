package com.clinicos.ui.nav;

/**
 * A drawer entry with its navigation route, Arabic copy, and an icon that the
 * Thymeleaf icon fragment maps to an inline SVG.
 */
public record NavSection(String key, String route, String label, String title, String subtitle, NavIcon icon) {
}
