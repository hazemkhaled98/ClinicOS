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

1. **Read and brainstorm.** Read the UC, its referenced business rules, and the matching design screen when one exists. Draft candidate scenarios before committing to a plan: main success flow, every specified alternative/error flow, authorization boundary, one data-persistence/reload check where applicable, plus business-rule edge cases (boundary values, repeated submission, missing data). Push past the obvious cases. Present the candidates to the user, let them trim/add/confirm coverage, and only then lock the plan.
2. **Publish the scenario table** before executing anything. One row per scenario:
   ```
   | ID | Scenario | Preconditions | Validation |
   ```
   `Preconditions` also states the executing role and tenant (multi-tenant app; wrong-tenant fencing is a real check). `Validation` is the expected visible outcome in the UI — what must be observable for the row to pass. Order rows: main flow first, then alternatives/errors, then boundary and persistence checks. Every flow promised by the UC must appear.
3. **Execute each scenario** through the rendered UI with Playwright, in row order, using the correct role and tenant. Record the exact inputs and the actual visible outcome. On failures and meaningful visual/interaction defects, capture a screenshot and save it to a fixed evidence location, then reference that path in the table.
4. **Reset to a clean baseline between rows** (close/re-open or navigate away) so prior scenarios do not leak state and contaminate the next validation.
5. **Keep testing after an individual failure** when later scenarios can run safely. Stop only when a defect prevents safe continuation or the local environment is unavailable.
6. **Report findings in the same table.** Extend it with result columns and fill every planned row:
   ```
   | ID | Scenario | Preconditions | Validation | Result | Actual outcome | Evidence |
   ```
   `Result` is one of `Pass`, `Fail`, `Blocked`, `Not run`. No row is left blank — a row that could not run gets a terminal `Result` (`Blocked`/`Not run`) plus a reason. For each bug, below the table include: scenario, reproduction steps, expected result, actual result, severity, and the smallest recommended fix area. Keep UX observations separate from defects and label them as suggestions unless they contradict the UC, DESIGN.md, accessibility basics, or prevent task completion.

## Verdict

End with one of: `Pass`, `Pass with UX suggestions`, `Fail`, or `Blocked`. The verdict derives mechanically from the table's `Result` column — no judgment call: any `Fail` → `Fail`; a forced stop or unresolvable blocker → `Blocked`; every row `Pass` with no defects → `Pass`; every row `Pass` with only advisory UX notes → `Pass with UX suggestions`. A failure blocks completion until the defect is fixed, its automated coverage is added or updated as appropriate, and this manual test is rerun. A blocked run is not a pass and does not authorize completion.

## Boundaries

- Scope scenarios strictly to the requested UC and directly referenced rules; report adjacent problems separately without expanding the test plan.
- Never expose credentials, tokens, or personally identifiable data in the report or screenshots.
- Recommendations are advisory. Apply fixes only when the user asks for implementation.
