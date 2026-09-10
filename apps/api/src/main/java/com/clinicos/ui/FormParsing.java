package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

final class FormParsing {

    private FormParsing() {
    }

    static BigDecimal parseAmount(String value, String key, Map<String, String> errors, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            errors.put(key, message);
            return null;
        }
    }

    static Integer parseInt(String value, String key, Map<String, String> errors, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            errors.put(key, message);
            return null;
        }
    }

    static LocalTime parseTime(String value, String key, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            errors.put(key, "وقت غير صحيح");
            return null;
        }
    }
}
