package com.clinicos.ui.nav;

public enum NavIcon {
    USER("user"), EDIT("edit"), BOLT("bolt"), TASKS("tasks"),
    CLIPBOARD_CHECK("clipboard-check"), STAR("star"), ARCHIVE("archive"), CHART("chart");

    private final String key;

    NavIcon(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
