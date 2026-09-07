package com.clinicos.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class StyleguideController {

    @GetMapping("/dev/styleguide")
    public String styleguide() {
        return "dev/styleguide";
    }
}
