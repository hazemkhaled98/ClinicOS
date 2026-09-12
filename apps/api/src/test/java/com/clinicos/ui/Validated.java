package com.clinicos.ui;

import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Builds a model-attribute BindingResult from the same Jakarta Bean
 * Validation constraints the web layer runs, so controller handlers can be
 * tested with a direct call instead of MockMvc standalone.
 */
final class Validated {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private Validated() {
    }

    static BindingResult of(Object form) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(form, "form");
        VALIDATOR.validate(form).forEach(v ->
                result.rejectValue(v.getPropertyPath().toString(), "invalid", v.getMessage()));
        return result;
    }
}