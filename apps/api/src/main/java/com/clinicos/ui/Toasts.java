package com.clinicos.ui;

import org.springframework.ui.Model;

/**
 * Shared toast plumbing for admin-dashboard cards. Sets the {@code toastMessage}
 * / {@code toastType} model attributes that every swapped card fragment root
 * binds via {@code th:attr="data-toast=...,data-toast-type=..."}; the
 * {@code htmx:afterSwap} listener in fragments/head.html then shows the toast.
 */
final class Toasts {

    private Toasts() {
    }

    static void success(Model model, String message) {
        model.addAttribute("toastMessage", message);
        model.addAttribute("toastType", "success");
    }

    static void error(Model model, String message) {
        model.addAttribute("toastMessage", message);
        model.addAttribute("toastType", "error");
    }
}