---
name: review-pr
description: Review an open pull request or supplied diff for code quality, tests, comments, error handling, type design, and unnecessary complexity. Use when pr-sentinel needs its correctness/quality pass or user asks for a comprehensive PR review.
---

# Review PR

Review only supplied diff range. If none supplied, require open PR; use base merge-base through `HEAD`.

Report only. Do not edit code.

- **Code:** project conventions, bugs, regressions. High-confidence findings only.
- **Tests:** changed behavior, error paths, boundaries, critical gaps.
- **Comments:** claims disagreeing with code, stale guidance, missing rationale.
- **Errors:** swallowed exceptions, unsafe fallbacks, missing logs, misleading feedback.
- **Types:** changed types. Rate encapsulation, invariant expression, usefulness, enforcement 1–10.
- **Simplify:** redundant code, abstractions, nesting. Preserve behavior.

Skip irrelevant passes. Every finding needs file:line, impact, evidence, concrete fix. Exclude nits and uncertain findings. State `No findings` when clean.
