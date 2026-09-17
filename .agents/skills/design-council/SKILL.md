---
name: design-council
description: "Bring an already-designed UI (Stitch or otherwise) to production quality through visual polish, UX refinement, interaction quality, and restrained motion, without changing its design direction. Use when: user says /design-council, or asks to polish, refine, or ship-harden an existing UI, or wants a report on a UI diff's visual/motion quality."
user-invocable: true
argument-hint: "<screen, component, or diff to polish>"
---

# Design Council

Takes an existing UI and brings it to production quality. It does not design,
redesign, or set direction — the current implementation (Stitch-generated or
otherwise) is the source of truth. Two authorities, one each for visual and
motion concerns, coordinated through a fixed inspect -> polish -> motion ->
review pipeline. Never a design essay: identified problems get fixed, not
described.

## Prime directive

**Polish, never redesign.** Touch something only against a concrete, named
defect: a usability problem, an accessibility failure, a consistency break,
or a quality gap. No named defect, no change. When in doubt, leave it.

## Authority table

| Concern | Authority |
|---|---|
| Typography, spacing, layout, hierarchy, color consistency, component consistency, responsive behavior, accessibility, visual polish, design-system consistency | `impeccable` |
| Interaction design, micro-interactions, motion principles, easing, duration, choreography, perceived responsiveness, motion restraint | `emil-design-eng` |
| Where motion would genuinely help (and where it would not) | `find-animation-opportunities` |
| Motion implementation | `animate` |

These do not overlap and therefore do not need arbitration: a motion question
never goes to `impeccable`; a visual/spacing/color question never goes to the
Emil trio. `find-animation-opportunities` only proposes; `animate` is the only
one that writes motion code. See `reference/precedence.md` for the residual
tie-break rules.

## Tooling note — invoking impeccable

`impeccable critique`, `impeccable polish`, `impeccable audit` (and `layout`,
`typeset`, `adapt`, `clarify`) are impeccable **skill workflows**, not CLI
verbs. Running one as a shell command fails with `Unknown command` and aborts
the pass — never do that. Execute a workflow by loading its spec and following
it: `.agents/skills/impeccable/reference/<name>.md` (e.g. `critique` ->
`reference/critique.md`, `polish` -> `reference/polish.md`). The only CLI verbs
these workflows need are `impeccable detect --json <targets>` and, where a spec
calls for persistence, `impeccable critique-storage <subcommand>`. In PR Review
Mode the target is the changed UI hunks, not a live surface: pass the changed
files to `impeccable detect` and judge findings against the diff.

## Phase 1 -- Inspect

Read the real surface before touching anything:

- The templates/components under change, plus the project's existing tokens
  and shared component classes (e.g. ClinicOS: `apps/api/src/main/styles/
  tokens.css` and `components.css`, `/dev/styleguide`).
- Existing transitions/animations already in the codebase.
- The matching design reference when one exists (e.g. ClinicOS: `ClinicOS
  Design/<NN>_*/screen.png` + `code.html` -- see that project's CLAUDE.md
  "Visual work stays out of main context" rule before reading any `screen.png`
  directly).
- `impeccable audit` for the technical floor (a11y, perf, responsive).

Treat the current implementation as intentional. This phase produces
understanding, not findings.

## Phase 2 -- Visual polish

1. `impeccable critique` on the target -> a list of concrete, named defects.
2. Rank by impact. Small, high-value fixes over sweeping changes.
3. Implement with `impeccable polish` (reach for `layout`, `typeset`,
   `adapt`, or `clarify` when the defect is specifically spacing/rhythm,
   typography, responsive, or copy).
4. Reuse existing tokens and component classes. No new color, font, or
   spacing value outside what the project's design system already defines.

## Phase 3 -- Motion discovery

Run `find-animation-opportunities` on the polished surface. Its output must
name what it rejects, not just what it proposes -- an opportunity list with
no rejected set has not actually applied restraint. Keep the proposed list
small: a handful of high-value moments, not a pass over every element.

## Phase 4 -- Motion implementation

`emil-design-eng` sets the principles for each accepted opportunity (which
properties, curve, duration, interruption behavior, exit). `animate`
implements it, following whatever animation approach the project already uses
before introducing anything new. `prefers-reduced-motion` support is
mandatory for every animation added here, not optional.

## Phase 5 -- Review

Check, in order: visual consistency, interaction states, responsive behavior,
accessibility, motion quality (`review-animations` if installed). Fix
whatever the review surfaces, then run one final `impeccable polish` pass and
stop -- this is a bounded pass, not an open-ended loop.

## Anti-redesign guardrails

Hard rules, no exceptions without a named reason:

- No redesigning a page without a concrete, stated problem.
- No replacing the existing visual language.
- No gradients, glassmorphism, or shadow inflation added for their own sake.
- No trendy effects added just to look impressive.
- No layout changes without a named problem driving them.
- No swapping a component for another approach merely by preference.
- No animating everything; no animating what does not benefit from it.
- Prefer small, high-impact improvements over broad ones.
- Preserve brand and product identity.

## Motion discipline

Motion communicates: what changed, where it came from, what was interacted
with, what state is being entered. It does not decorate.

Avoid: excessive bounce, gratuitous parallax, long transitions, constant
movement, animation that delays interaction, animation that shifts layout,
motion disconnected from what triggered it. Always honor
`prefers-reduced-motion`.

## Implementation-first

This skill is for an agent working in the codebase, not producing a design
document. When a concrete improvement is identified, implement it -- don't
just describe it. Inspect the actual code and design system before making
broad changes. Reuse existing components, tokens, utilities, and patterns.

## PR Review Mode

Trigger: a caller passes a list of changed UI files plus a way to get their
hunks (the hunks themselves, or a diff range + file list). Target is the
diff, not a live surface.

- **Gate first.** Run the changed-file list through the UI-file gate. A file
  counts as UI if it ends in `.tsx .jsx .vue .svelte .css .scss .html .ts
  .js`. Zero matches -> report "no frontend changes in this diff" and stop.
  Repos can render UI from other extensions -- e.g. ClinicOS ships
  server-rendered views as `.java` + `.html` Thymeleaf templates -- so check
  the repo's changed files for such UI-bearing extensions before declaring
  the diff frontend-free. When borderline, run; one wasted pass costs
  nothing, a skipped finding costs the review.
- **Phase 1-2 (visual) -- applied in place.** `impeccable critique` scoped to
  the changed hunks produces numbered findings (element, problem, fix). Show
  them and **ask for approval before editing** -- this is a shared PR, not a
  scratch diff. Once approved, apply with `impeccable polish` scoped to only
  the files the diff touched, and commit that pass on its own. A finding on
  code the diff did not touch gets reported, never fixed.
- **Phase 3-4 (motion) -- deferred, not dropped.** `find-animation-opportunities`
  on the diff (plus `review-animations` for motion/CSS hunks) produces a
  proposal list with its rejected set. These are **not** built here -- they
  are handed back as findings for the caller to aggregate into its own report
  alongside its other review passes. If a motion finding is later approved,
  build it with `animate` under `emil-design-eng` principles as part of that
  caller's own apply flow.
- **Phase 5 does not run in PR mode** -- the PR's own review passes are the
  review.
- **Output: two labeled blocks.**
  1. *Applied* -- what `impeccable polish` changed, plus its commit SHA.
  2. *Proposed motion* -- numbered (element, problem, fix), shaped for a
     yes/no selection flow.
