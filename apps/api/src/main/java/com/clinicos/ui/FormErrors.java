package com.clinicos.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

final class FormErrors {

    private FormErrors() {
    }

    static Map<String, String> of(BindingResult binding) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : binding.getFieldErrors()) {
            errors.put(fieldError.getField(),
                    fieldError.getDefaultMessage() == null ? "قيمة غير صحيحة" : fieldError.getDefaultMessage());
        }
        return errors;
    }
}