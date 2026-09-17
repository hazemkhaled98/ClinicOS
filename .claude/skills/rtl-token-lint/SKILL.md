---
name: rtl-token-lint
description: Scan changed Thymeleaf templates, components.css, or Tailwind classes for physical CSS properties (left/right/pl-/pr-/ml-/mr-/-translate-x) or raw hex colors that violate ClinicOS's RTL-logical-properties-only and DESIGN.md-token-only rules. Use before finishing any UI/template/CSS change, and especially after porting markup from Stitch design comps.
---

# RTL / Token Lint

DESIGN.md and CLAUDE.md require, without exception:
- Logical CSS properties only: `padding-inline-start/end`, `margin-inline`, `border-inline-start`, `inset-inline` — never `left`/`right`/`padding-left`/etc.
- Logical Tailwind utilities only: `ps-`/`pe-` (padding), `ms-`/`me-` (margin), `border-s`/`border-e`, `start-`/`end-` — never `pl-`, `pr-`, `ml-`, `mr-`, `left-`, `right-`, `-translate-x`.
- No raw hex (`bg-[#...]` or CSS hex literals) — every color must come from the Tailwind `@theme` palette in `apps/api/src/main/styles/tokens.css`, sourced from DESIGN.md.

This class of bug has recurred in this repo (danger hex refs, hover specificity fixes) — check for it explicitly rather than trusting a visual pass.

## When invoked

1. Grep the changed files (Thymeleaf templates under `apps/api/src/main/resources/templates/`, `apps/api/src/main/styles/components.css`, or any file with Tailwind classes) for:
   - Physical Tailwind utilities: `\b(pl|pr|ml|mr|left|right)-\d`, `-translate-x`
   - Physical CSS properties: `\b(left|right|padding-left|padding-right|margin-left|margin-right|border-left|border-right)\s*:`
   - Raw hex: `#[0-9a-fA-F]{3,8}\b` outside `tokens.css`
2. For each hit, report file:line and the required logical/token replacement.
3. If a Stitch comp (`ClinicOS Design/*/code.html`) is the source, note that its physical-direction markup is expected to be converted — this is not a comp bug, it's an untranslated port.
4. If a color need isn't covered by an existing token in `tokens.css`, say so explicitly rather than suggesting a hardcoded value — DESIGN.md must be extended first.

## Output

List violations only; skip clean files. If nothing found, say so briefly.
