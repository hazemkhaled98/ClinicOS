package com.clinicos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ClinicOS: multi-tenant SaaS revamp of the 3yadty dental clinic staff
 * management app. See {@code docs/roadmap.md} for the build plan and
 * per-phase status.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
