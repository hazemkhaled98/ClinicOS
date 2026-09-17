package com.clinicos.prep;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PrepChecklistService {
    List<Checklist> list(UUID clinicId);
    Checklist get(UUID clinicId, UUID checklistId);
    List<Template> templates();
    Checklist importTemplate(UUID clinicId, Actor actor, String templateCode);
    Checklist save(UUID clinicId, Actor actor, UUID checklistId, ChecklistRequest request);
    void archive(UUID clinicId, Actor actor, UUID checklistId);
    Checklist approve(UUID clinicId, Actor actor, UUID checklistId);
    Checklist unapprove(UUID clinicId, Actor actor, UUID checklistId);
    Run today(UUID clinicId, Actor actor, UUID checklistId);
    Run toggle(UUID clinicId, Actor actor, UUID checklistId, UUID itemId, boolean checked);
    Run reset(UUID clinicId, Actor actor, UUID checklistId);

    static void validate(ChecklistRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("أدخل اسم القائمة");
        }
        if (request.sections() == null || request.sections().isEmpty()) {
            throw new IllegalArgumentException("أضف قسمًا واحدًا على الأقل");
        }
        for (var section : request.sections()) {
            if (section == null || section.title() == null || section.title().isBlank()) {
                throw new IllegalArgumentException("أدخل اسم القسم");
            }
            if (section.items() == null || section.items().isEmpty()) {
                throw new IllegalArgumentException("يجب أن يحتوي كل قسم على عنصر واحد على الأقل");
            }
            for (var item : section.items()) {
                if (item == null || item.name() == null || item.name().isBlank()) {
                    throw new IllegalArgumentException("أدخل اسم العنصر");
                }
            }
        }
    }

    record Actor(UUID membershipId, String roleCode, UUID employeeId) { }

    record ChecklistRequest(String name, List<SectionRequest> sections) {
        public ChecklistRequest {
            name = name == null ? null : name.trim();
        }
    }

    record SectionRequest(String title, List<ItemRequest> items) {
        public SectionRequest {
            title = title == null ? null : title.trim();
        }
    }

    record ItemRequest(String name) {
        public ItemRequest {
            name = name == null ? null : name.trim();
        }
    }

    record Checklist(UUID id, String name, String status, UUID approvedBy, List<Section> sections) {
        public int itemCount() {
            return sections.stream().mapToInt(section -> section.items().size()).sum();
        }
    }

    record Section(UUID id, String title, List<Item> items) { }
    record Item(UUID id, String name, boolean checked) { }

    record Template(String code, String name, List<Section> sections) {
        public int itemCount() {
            return sections.stream().mapToInt(section -> section.items().size()).sum();
        }
    }

    record Run(UUID id, LocalDate runDate, int checkedCount, int totalCount, List<Section> sections) { }
}
