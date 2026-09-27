package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ClinicIdentityTemplateTest {

    @Test
    void invalidSlugIsExplainedBesideTheFieldAfterSubmit() {
        var form = new ClinicSettingsController.IdentityForm();
        form.setName("عيادة النور");
        form.setSlug("غير-صالح");
        Context context = new Context(Locale.ROOT);
        context.setVariable("identityForm", form);
        context.setVariable("fieldErrors", Map.of("slug", "استخدم أحرفًا إنجليزية"));

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        String html = engine.process("admin/clinic-settings", Set.of("clinicInfoCard"), context);

        assertThat(html).contains("استخدم أحرفًا إنجليزية", "aria-invalid=\"true\"",
                "aria-describedby=\"clinic-slug-help clinic-slug-error\"", "value=\"غير-صالح\"");
    }

    @Test
    void aSavedCardCarriesTheNameSoChromeCanUpdateWithoutAReload() throws Exception {
        var form = new ClinicSettingsController.IdentityForm();
        form.setName("عيادة النور");
        form.setSlug("bright-smile");
        Context context = new Context(Locale.ROOT);
        context.setVariable("identityForm", form);
        context.setVariable("fieldErrors", Map.of());

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        String html = engine.process("admin/clinic-settings", Set.of("clinicInfoCard"), context);

        assertThat(html).contains("data-clinic-name=\"عيادة النور\"");
        assertThat(Files.readString(Path.of("src/main/resources/templates/fragments/head.html")))
                .contains("data-clinic-name", "clinicos-clinic-name");
    }

    @Test
    void aRejectedCardCarriesNoNameSoChromeKeepsTheSavedOne() {
        var form = new ClinicSettingsController.IdentityForm();
        form.setName("اسم لم يُحفظ");
        form.setSlug("-bad-slug-");
        Context context = new Context(Locale.ROOT);
        context.setVariable("identityForm", form);
        context.setVariable("fieldErrors", Map.of("slug", "كود غير صالح"));

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        String html = engine.process("admin/clinic-settings", Set.of("clinicInfoCard"), context);

        assertThat(html).doesNotContain("data-clinic-name");
    }
}
