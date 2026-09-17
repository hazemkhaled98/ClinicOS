package com.clinicos.prep.internal;

import static com.clinicos.shared.jooq.tables.PrepTemplate.PREP_TEMPLATE;
import static com.clinicos.shared.jooq.tables.PrepTemplateItem.PREP_TEMPLATE_ITEM;
import static com.clinicos.shared.jooq.tables.PrepTemplateSection.PREP_TEMPLATE_SECTION;
import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;

@SpringBootTest(classes = Application.class)
class PrepTemplateCatalogIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DSLContext dsl;

    @Test
    void catalogIsSeededAndOrderedAndReadOnly() {
        assertThat(dsl.selectCount().from(PREP_TEMPLATE).fetchOne(0)).isEqualTo(3);
        assertThat(dsl.select(PREP_TEMPLATE.CODE).from(PREP_TEMPLATE)
                .orderBy(PREP_TEMPLATE.DISPLAY_ORDER).fetch(PREP_TEMPLATE.CODE))
                .containsExactly("examination", "anesthesia", "endo");
        assertThat(dsl.select(PREP_TEMPLATE_SECTION.TITLE).from(PREP_TEMPLATE_SECTION)
                .join(PREP_TEMPLATE).on(PREP_TEMPLATE.ID.eq(PREP_TEMPLATE_SECTION.TEMPLATE_ID))
                .where(PREP_TEMPLATE.CODE.eq("endo"))
                .orderBy(PREP_TEMPLATE_SECTION.DISPLAY_ORDER)
                .fetch(PREP_TEMPLATE_SECTION.TITLE))
                .containsExactly("تجهيز عام", "أكسيس وتنظيف الكانالز");
        assertThat(dsl.selectCount().from(PREP_TEMPLATE_ITEM).fetchOne(0)).isEqualTo(18);
        assertThat((Boolean) dsl.fetchValue(
                "select has_table_privilege(current_user, 'prep_template', 'insert')", Boolean.class))
                .isFalse();
    }
}
