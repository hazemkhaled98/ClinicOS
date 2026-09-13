package com.clinicos.ui;

import java.util.HashSet;
import java.util.List;

import com.clinicos.ui.nav.NavSection;
import com.clinicos.ui.nav.NavSectionResolver;

/**
 * Decides which section a freshly-logged-in (or root-visited) user lands on,
 * born from {@code HelloView}: the remembered {@code lastSection} cookie wins
 * when the user may still open it, otherwise the first permitted section.
 * Returns {@code null} when there is nothing to open (no permissions, or no
 * session state at all).
 */
public final class SectionFocus {

    private SectionFocus() {
    }

    @SuppressWarnings("unchecked")
    public static String resolveTarget(String lastSectionCookie, Object codesAttr, Object roleCodeAttr) {
        if (!(codesAttr instanceof List<?> codesRaw) || !(roleCodeAttr instanceof String roleCode)) {
            return null;
        }
        List<String> codes = (List<String>) codesRaw;
        if (codes.contains("ceo") && "owner".equals(roleCode)) {
            return "admin-dashboard";
        }
        List<NavSection> sections = NavSectionResolver.resolve(new HashSet<>(codes), roleCode);
        if (sections.isEmpty()) {
            return null;
        }
        if (lastSectionCookie != null && sections.stream().anyMatch(s -> s.route().equals(lastSectionCookie))) {
            return lastSectionCookie;
        }
        return sections.getFirst().route();
    }
}
