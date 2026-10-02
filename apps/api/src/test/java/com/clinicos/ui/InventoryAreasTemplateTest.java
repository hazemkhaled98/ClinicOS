package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class InventoryAreasTemplateTest {

    @Test
    void everyInventoryAreaRendersItsOwnIcon() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        List<InventoryController.SubArea> areas = List.of(
                new InventoryController.SubArea("items", "manage", "الأصناف", "items"),
                new InventoryController.SubArea("tray", "tray", "صينية التحضير", "tray"),
                new InventoryController.SubArea("issue", "issue", "صرف المخزون", "issue"),
                new InventoryController.SubArea("orders", "orders", "النواقص والطلب", "orders"),
                new InventoryController.SubArea("receive", "receive", "الاستلام", "receive"),
                new InventoryController.SubArea("received", "received", "سجل الاستلام", "received"),
                new InventoryController.SubArea("returns", "returns", "المرتجعات", "returns"),
                new InventoryController.SubArea("suppliers", "suppliers", "الموردين", "suppliers"),
                new InventoryController.SubArea("procs", "procs", "قوائم الإجراءات", "procs"),
                new InventoryController.SubArea("myprocs", "myprocs", "سجل إجراءاتي", "myprocs"),
                new InventoryController.SubArea("dash", "dash", "لوحة المخزون", "dash"),
                new InventoryController.SubArea("profit", "profit", "الربحية", "profit"),
                new InventoryController.SubArea("analytics", "analytics", "تحليل الاستهلاك", "analytics"),
                new InventoryController.SubArea("waste", "waste", "الهدر", "waste"),
                new InventoryController.SubArea("doctors", "doctors", "تحليل الأطباء", "doctors"),
                new InventoryController.SubArea("supAnalysis", "supAnalysis", "تحليل الموردين", "supAnalysis"),
                new InventoryController.SubArea("itemAnalysis", "itemAnalysis", "تحليل الأصناف", "itemAnalysis"),
                new InventoryController.SubArea("ledger", "ledger", "سجل الحركة", "ledger"),
                new InventoryController.SubArea("approvals", "approvals", "طلبات الموافقة", "approvals"));
        WebContext context = new WebContext(
                JakartaServletWebApplication.buildApplication(new MockServletContext())
                        .buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse()),
                Locale.ROOT,
                Map.of(
                        "_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"),
                        "layout", new LayoutModel.LayoutData(List.of(), "مالك", "عيادتي", "owner", "2026-10-02", "inventory"),
                        "areas", areas));

        String html = engine.process("inventory", context);
        assertThat(html).contains(
                "href=\"/icons/inventory.svg#items\"", "href=\"/icons/inventory.svg#tray\"",
                "href=\"/icons/inventory.svg#issue\"", "href=\"/icons/inventory.svg#orders\"",
                "href=\"/icons/inventory.svg#receive\"", "href=\"/icons/inventory.svg#received\"",
                "href=\"/icons/inventory.svg#returns\"", "href=\"/icons/inventory.svg#suppliers\"",
                "href=\"/icons/inventory.svg#procs\"", "href=\"/icons/inventory.svg#myprocs\"",
                "href=\"/icons/inventory.svg#dash\"", "href=\"/icons/inventory.svg#profit\"",
                "href=\"/icons/inventory.svg#analytics\"", "href=\"/icons/inventory.svg#waste\"",
                "href=\"/icons/inventory.svg#doctors\"", "href=\"/icons/inventory.svg#supAnalysis\"",
                "href=\"/icons/inventory.svg#itemAnalysis\"", "href=\"/icons/inventory.svg#ledger\"",
                "href=\"/icons/inventory.svg#approvals\"");
    }
}
