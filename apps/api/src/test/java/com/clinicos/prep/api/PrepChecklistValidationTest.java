package com.clinicos.prep.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PrepChecklistValidationTest {

    @Test
    void saveRejectsNoSections() {
        var request = new PrepChecklistService.ChecklistRequest("كشف", List.of());
        assertThatThrownBy(() -> PrepChecklistService.validate(request))
                .hasMessage("أضف قسمًا واحدًا على الأقل");
    }

    @Test
    void saveRejectsSectionWithoutItems() {
        var request = new PrepChecklistService.ChecklistRequest(
                "كشف", List.of(new PrepChecklistService.SectionRequest("عام", List.of())));
        assertThatThrownBy(() -> PrepChecklistService.validate(request))
                .hasMessage("يجب أن يحتوي كل قسم على عنصر واحد على الأقل");
    }

    @Test
    void validationTrimsAcceptedText() {
        var request = new PrepChecklistService.ChecklistRequest(
                " كشف ", List.of(new PrepChecklistService.SectionRequest(
                        " عام ", List.of(new PrepChecklistService.ItemRequest(" أداة ")))));
        PrepChecklistService.validate(request);
        assertThat(request.name()).isEqualTo("كشف");
        assertThat(request.sections().getFirst().title()).isEqualTo("عام");
        assertThat(request.sections().getFirst().items().getFirst().name()).isEqualTo("أداة");
    }
}
