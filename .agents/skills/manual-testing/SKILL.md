---
name: manual-testing
description: Manually validate a completed ClinicOS use case in a real browser after coverage is clean and before its branch is marked complete. Generates UC-derived scenarios, executes them with Playwright, and reports reproducible defects plus optional UX improvements; it does not implement fixes.
---

# Manual Testing

Run this on the use-case branch after `/coverage-check` reports no open implementation or test gaps, and before the use case or phase is marked complete. It is a release gate, not a replacement for automated tests or coverage review.

## Preconditions

- Resolve the requested `UC-XXX` to a specification in `docs/use_cases/`; stop if it is missing or coverage remains open.
- Confirm a locally runnable application and suitable test accounts/data exist. Do not start `dev-up.ps1`, reset or seed data, or alter source code unless the user separately authorizes it.
- Use Playwright to drive the real browser UI. Do not treat controller tests, page source, or a direct HTTP request as manual-test evidence.

## Workflow

1. Read the UC, its referenced business rules, and the matching design screen when one exists. Produce a concise scenario list before testing: main success flow, every specified alternative/error flow, authorization boundary, and one data-persistence/reload check where applicable. State preconditions and expected result for each scenario.
2. Execute each scenario through the rendered UI with Playwright. Use the correct role and tenant; record the exact inputs and visible outcome. Capture a screenshot or equivalent browser evidence for failures and meaningful visual/interaction defects.
3. Keep testing after an individual failure when later scenarios can run safely. Stop only when a defect prevents safe continuation or the local environment is unavailable; explicitly identify unexecuted scenarios.
4. Report results; do not modify the application or mark any status complete. For each bug include: scenario, reproduction steps, expected result, actual result, severity, and the smallest recommended fix area. Keep UX observations separate from defects and label them as suggestions unless they contradict the UC, DESIGN.md, accessibility basics, or prevent task completion.

## Verdict

End with one of: `Pass`, `Pass with UX suggestions`, `Fail`, or `Blocked`. A pass requires every required scenario to execute successfully. A failure blocks completion until the defect is fixed, its automated coverage is added or updated as appropriate, and this manual test is rerun. A blocked run is not a pass and does not authorize completion.

## Boundaries

- Scope scenarios strictly to the requested UC and directly referenced rules; report adjacent problems separately without expanding the test plan.
- Never expose credentials, tokens, or personally identifiable data in the report or screenshots.
- Recommendations are advisory. Apply fixes only when the user asks for implementation.
