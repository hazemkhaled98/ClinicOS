package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import com.clinicos.staff.api.LeaveRequestService.LeaveRequest;
import com.clinicos.staff.api.LeaveRequestService.LeaveStatus;

import org.springframework.security.web.csrf.DefaultCsrfToken;

class LeaveTemplateTest {

    @Test
    void ownRequestsScreenRendersFormHistoryAndDecisionNote() {
        LeaveRequest request = request(LeaveStatus.rejected, "غير مناسب هذا الأسبوع");
        WebContext context = context("assistant", "leaves/me", Map.of(
                "errors", Map.of(),
                "start", "",
                "end", "",
                "reason", "",
                "canApprove", false,
                "pendingCount", 0,
                "hasEmployee", true,
                "canRequest", true,
                "requests", List.of(request)));

        String html = templateEngine().process("leaves-me", context);

        assertThat(html).contains("name=\"start\"", "name=\"end\"", "name=\"reason\"",
                "سجل طلباتي", "غير مناسب هذا الأسبوع", "مرفوضة", "12/10/2026", "14/10/2026")
                .doesNotContain("معتمدة", "بانتظار", "Oct");
    }

    @Test
    void approverQueueRendersRequestAndDecisionForms() {
        WebContext context = context("manager", "leaves", Map.of("pending", List.of(request(LeaveStatus.pending, null))));

        String html = templateEngine().process("leaves-queue", context);

        assertThat(html).contains("طلبات الإجازة", "سارة", "/leaves/", "اعتماد", "رفض", "name=\"note\"",
                "w-full sm:w-auto", "12/10/2026", "14/10/2026", "<h2>بانتظار الاعتماد</h2>")
                .doesNotContain("<h3>بانتظار الاعتماد</h3>", "Oct");
    }

    @Test
    void approvedAndPendingStatusesRenderOnlyTheirOwnChip() {
        String approved = ownRequestsHtml(request(LeaveStatus.approved, null));
        String pending = ownRequestsHtml(request(LeaveStatus.pending, null));

        assertThat(approved).contains("معتمدة").doesNotContain("مرفوضة", "بانتظار");
        assertThat(pending).contains("بانتظار").doesNotContain("معتمدة", "مرفوضة");
    }

    @Test
    void emptyApproverQueueRemainsReachableFromOwnRequestsScreen() {
        WebContext context = context("manager", "leaves/me", Map.of(
                "errors", Map.of(),
                "start", "",
                "end", "",
                "reason", "",
                "canApprove", true,
                "pendingCount", 0,
                "hasEmployee", true,
                "canRequest", true,
                "requests", List.of()));

        String html = templateEngine().process("leaves-me", context);

        assertThat(html).contains("href=\"/leaves\"", "0", "طلبات بانتظار الموافقة");
    }

    @Test
    void soleOwnerSeesHolidaySettingsInsteadOfRequestForm() {
        WebContext context = context("owner", "leaves/me", Map.of(
                "errors", Map.of(),
                "start", "",
                "end", "",
                "reason", "",
                "canApprove", true,
                "pendingCount", 0,
                "hasEmployee", true,
                "soleOwner", true,
                "canRequest", false,
                "requests", List.of()));

        String html = templateEngine().process("leaves-me", context);

        assertThat(html).contains("بصفتك المالك الوحيد", "href=\"/admin-dashboard/settings\"")
                .doesNotContain("name=\"start\"", "ابدأ بأول طلب من النموذج فوق.");
    }

    @Test
    void overlapFailureIsVisibleBesideThePreservedForm() {
        WebContext context = context("assistant", "leaves/me", Map.of(
                "errors", Map.of(),
                "start", "2026-10-05",
                "end", "2026-10-06",
                "reason", "تكرار",
                "formError", "توجد إجازة مسجلة بنفس الفترة",
                "canApprove", false,
                "pendingCount", 0,
                "hasEmployee", true,
                "canRequest", true,
                "requests", List.of()));

        String html = templateEngine().process("leaves-me", context);

        assertThat(html).contains("role=\"alert\"", "توجد إجازة مسجلة بنفس الفترة",
                "value=\"2026-10-05\"", "value=\"2026-10-06\"", "تكرار");
    }

    @Test
    void redirectSuccessFeedbackIsVisible() {
        WebContext context = context("assistant", "leaves/me", Map.ofEntries(
                Map.entry("errors", Map.of()),
                Map.entry("start", ""),
                Map.entry("end", ""),
                Map.entry("reason", ""),
                Map.entry("canApprove", false),
                Map.entry("pendingCount", 0),
                Map.entry("hasEmployee", true),
                Map.entry("canRequest", true),
                Map.entry("requests", List.of()),
                Map.entry("toastMessage", "تم إرسال طلب الإجازة"),
                Map.entry("toastType", "success")));

        String html = templateEngine().process("leaves-me", context);

        assertThat(html).contains("تم إرسال طلب الإجازة", "role=\"status\"");
    }

    @Test
    void redirectErrorFeedbackUsesAlertRole() {
        WebContext context = context("manager", "leaves", Map.of(
                "pending", List.of(),
                "toastMessage", "اكتب سبب الرفض",
                "toastType", "error"));

        String html = templateEngine().process("leaves-queue", context);

        assertThat(html).contains("اكتب سبب الرفض", "role=\"alert\"");
    }

    private static WebContext context(String role, String activeRoute, Map<String, Object> variables) {
        Map<String, Object> model = new java.util.HashMap<>(variables);
        model.put("layout", new LayoutModel.LayoutData(List.of(), "sara", "عيادتي", role, "التاريخ", activeRoute));
        model.putIfAbsent("toastMessage", "");
        model.putIfAbsent("toastType", "");
        model.put("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        return new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.forLanguageTag("ar"), model);
    }

    private static String ownRequestsHtml(LeaveRequest request) {
        WebContext context = context("assistant", "leaves/me", Map.of(
                "errors", Map.of(),
                "start", "",
                "end", "",
                "reason", "",
                "canApprove", false,
                "pendingCount", 0,
                "hasEmployee", true,
                "canRequest", true,
                "requests", List.of(request)));
        return templateEngine().process("leaves-me", context);
    }

    private static LeaveRequest request(LeaveStatus status, String note) {
        return new LeaveRequest(UUID.randomUUID(), UUID.randomUUID(), "سارة",
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14), "ظرف عائلي", status,
                null, note, OffsetDateTime.now());
    }

    private static SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
