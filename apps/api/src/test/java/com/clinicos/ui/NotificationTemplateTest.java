package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class NotificationTemplateTest {

    @Test
    void unreadCountPulsesOnlyWhenUnread() {
        assertThat(render(3)).contains("notification-count--pulse").doesNotContain("hidden");
        assertThat(render(0)).contains("hidden").doesNotContain("notification-count--pulse");
    }

    @Test
    void pollingTriggerLivesOnWrapperOutsideBadgeSwapTarget() {
        String rendered = render(3);
        String poller = openingTagWithId(rendered, "notification-count-poller", 0);
        String badge = openingTagWithId(rendered, "notification-count", rendered.indexOf(poller));

        assertThat(poller).contains("hx-get=\"/notifications/badge\"",
                "hx-target=\"#notification-count\"",
                "hx-trigger=\"load, every 30s, notificationsChanged from:body\"");
        assertThat(badge).doesNotContain("hx-get", "hx-trigger");
        assertThat(rendered.indexOf(poller)).isLessThan(rendered.indexOf(badge));
    }

    @Test
    void anonymousBadgeKeepsSwapTargetId() throws Exception {
        String source;
        try (var in = getClass().getResourceAsStream("/templates/fragments/notifications.html")) {
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        String emptyBadge = source.lines()
                .filter(line -> line.contains("th:fragment=\"emptyBadge\""))
                .findFirst()
                .orElseThrow();

        assertThat(emptyBadge).contains("id=\"notification-count\"");
    }

    @Test
    void topbarMountsPersistentBadgePoller() {
        Context context = new Context(Locale.ROOT);
        context.setVariable("layout", new LayoutModel.LayoutData(
                List.of(), "مالك", "عيادتي", "owner", "التاريخ", "/"));

        String rendered = templateEngine().process("fragments/topbar", context);

        assertThat(rendered).contains("id=\"notification-count-poller\"", "id=\"notification-count\"");
    }

    private String render(int unread) {
        Context context = new Context(Locale.ROOT);
        context.setVariable("unread", unread);
        context.setVariable("notifications", List.of());
        context.setVariable("toastMessage", "");
        context.setVariable("toastType", "");

        return templateEngine().process("fragments/notifications", context);
    }

    private SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private String openingTagWithId(String rendered, String id, int fromIndex) {
        int start = rendered.indexOf("id=\"" + id + "\"", fromIndex);
        assertThat(start).isGreaterThanOrEqualTo(0);
        return rendered.substring(start, rendered.indexOf('>', start) + 1);
    }
}
