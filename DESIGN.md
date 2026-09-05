---
version: 1.0
name: ClinicOS Design System
description: A data-dense clinic back-office design system for Arabic RTL environments. Built on IBM Carbon discipline — light canvas, near-square corners, hairline borders (1px), flat tiles, single teal accent, and explicit Arabic/Latin typography with no negative tracking. Vaadin Flow theme mapping included.

colors:
  # Primary & Brand
  primary: "#17786a"
  primary-50pct: "#abb0ad"
  on-primary: "#ffffff"
  
  # Neutrals (Light)
  canvas: "#f4f7f6"
  surface: "#ffffff"
  hairline: "#e1e8e5"
  ink: "#16201d"
  ink-muted: "#5c6b66"
  table-header-bg: "#eef3f1"
  zebra-row: "#f8faf9"
  
  # Semantic Status
  status-success-deep: "#166534"
  status-success-bg: "#e3f6ea"
  status-success: "#1f7a4d"
  status-warning-deep: "#8a5a00"
  status-warning-bg: "#fef3d6"
  status-warning: "#b87900"
  status-danger-deep: "#9d2b1f"
  status-danger-bg: "#fce4e1"
  status-danger: "#b23b30"
  status-info-deep: "#1d4ed8"
  status-info-bg: "#e3edfb"
  status-info: "#2563eb"
  
  # Dark Theme (Reserved, Not Implemented)
  dark-canvas: "#0e1613"
  dark-surface: "#182420"
  dark-ink: "#eaf1ee"
  dark-hairline: "#28362f"
  dark-ink-muted: "#93a49b"
  dark-table-header-bg: "#1f2b26"
  dark-zebra-row: "#141d1a"
  dark-primary: "#2dd4bf"
  dark-drawer-start: "#0a2e29"
  dark-drawer-end: "#051917"
  dark-status-success-deep: "#4ade80"
  dark-status-success-bg: "#0f2b1b"
  dark-status-warning-deep: "#fbbf24"
  dark-status-warning-bg: "#332a10"
  dark-status-danger-deep: "#fb7185"
  dark-status-danger-bg: "#331613"
  dark-status-info-deep: "#60a5fa"
  dark-status-info-bg: "#12233a"

typography:
  page-title:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 28px
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: 0
  section-title:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 20px
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: 0
  card-title:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: 0
  card-title-numeric:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: 0
    fontFeature: "'tnum' 1"
  body:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
  body-numeric:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
    fontFeature: "'tnum' 1"
  body-sm:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: 0
  label:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 12px
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: 0
  label-numeric:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 12px
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: 0
    fontFeature: "'tnum' 1"
  caption:
    fontFamily: "'Cairo', 'Segoe UI', Tahoma, sans-serif"
    fontSize: 11px
    fontWeight: 400
    lineHeight: 1.3
    letterSpacing: 0

rounded:
  xs: 2px
  sm: 6px
  md: 8px
  pill: 9999px

spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  xxl: 32px

components:
  app-drawer:
    backgroundColor: "linear-gradient(180deg, {colors.dark-drawer-start}, {colors.dark-drawer-end})"
    textColor: "{colors.dark-ink}"
    width: "240px"
    padding: "{spacing.lg}"
  nav-item:
    backgroundColor: transparent
    textColor: "{colors.dark-ink-muted}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    padding: "{spacing.sm} {spacing.md}"
  nav-item-hover:
    backgroundColor: "rgba(255, 255, 255, 0.05)"
    textColor: "{colors.dark-ink}"
  nav-item-active:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
  topbar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    padding: "{spacing.md} {spacing.lg}"
    height: "56px"
  page-header:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    padding: "{spacing.lg} {spacing.lg} {spacing.md}"
  data-grid:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    padding: "{spacing.sm} {spacing.md}"
  data-grid-header:
    backgroundColor: "{colors.table-header-bg}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.label}"
    padding: "{spacing.sm} {spacing.md}"
  status-badge:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    padding: "{spacing.xs} {spacing.sm}"
  status-badge-success:
    backgroundColor: "{colors.status-success-bg}"
    textColor: "{colors.status-success-deep}"
  status-badge-warning:
    backgroundColor: "{colors.status-warning-bg}"
    textColor: "{colors.status-warning-deep}"
  status-badge-danger:
    backgroundColor: "{colors.status-danger-bg}"
    textColor: "{colors.status-danger-deep}"
  status-badge-info:
    backgroundColor: "{colors.status-info-bg}"
    textColor: "{colors.status-info-deep}"
  status-dot-success:
    backgroundColor: "{colors.status-success}"
    rounded: "{rounded.pill}"
    size: "8px"
  status-dot-warning:
    backgroundColor: "{colors.status-warning}"
    rounded: "{rounded.pill}"
    size: "8px"
  status-dot-danger:
    backgroundColor: "{colors.status-danger}"
    rounded: "{rounded.pill}"
    size: "8px"
  status-dot-info:
    backgroundColor: "{colors.status-info}"
    rounded: "{rounded.pill}"
    size: "8px"
  score-badge:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.label-numeric}"
    rounded: "{rounded.sm}"
    padding: "{spacing.xs} {spacing.sm}"
  form-field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "{spacing.sm} {spacing.md}"
  form-field-focus:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
  form-card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "{spacing.lg}"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    padding: "{spacing.sm} {spacing.lg}"
  button-primary-hover:
    backgroundColor: "#146c5f"
    textColor: "{colors.on-primary}"
  button-primary-active:
    backgroundColor: "#136659"
    textColor: "{colors.on-primary}"
  button-primary-disabled:
    backgroundColor: "{colors.primary-50pct}"
    textColor: "{colors.ink}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    padding: "{spacing.sm} {spacing.lg}"
  button-secondary-hover:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
  button-danger:
    backgroundColor: "{colors.status-danger}"
    textColor: "{colors.on-primary}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    padding: "{spacing.sm} {spacing.lg}"
  button-danger-hover:
    backgroundColor: "#a03528"
    textColor: "{colors.on-primary}"
  empty-state:
    backgroundColor: transparent
    padding: "{spacing.xxl}"
  empty-state-icon:
    textColor: "{colors.ink-muted}"
    size: "64px"
  empty-state-message:
    textColor: "{colors.ink-muted}"
    typography: "{typography.body}"
  dialog:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "{spacing.lg}"
    width: "400px"
  toast:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.md}"
    padding: "{spacing.md} {spacing.lg}"

---

## Overview

ClinicOS is a data-dense clinic back-office built on Vaadin Flow and Spring Boot, designed for Arabic RTL environments. The design system follows IBM Carbon discipline: a light canvas, near-square corners, hairline borders as the primary elevation cue, a flat tile-based layout, and a single structural teal accent (`#17786a`). There are no decorative drop shadows — depth is signalled by a 1px border and, sparingly, by a barely-there modal shadow for dialogs. Type is set in **Cairo** (a typeface that supports Arabic and Latin seamlessly) with no negative tracking, which would degrade Arabic glyph joining. All logical CSS properties (padding-inline, margin-block, border-inline-start, etc.) are used throughout; `left` and `right` are never used, ensuring correct RTL layout.

**Key Characteristics:**
- Light neutral canvas `{colors.canvas}` with white surfaces `{colors.surface}` for contrast
- Single teal structural accent `{colors.primary}` reserved for primary actions and active states
- Arabic-first typeface (`Cairo`) with no negative letter-spacing; Latin text and numbers inside Arabic paragraphs remain LTR-embedded
- Carbon-style flat design: 1px hairline borders as the only elevation cue, no decorative shadows
- Explicit semantic color ramp for status (success, warning, danger, info), each with `-deep` (text), `-bg` (tinted background), and base value
- Score-badge component maps performance bands (≥85, 60–84, <60) to the semantic ramp
- All CSS uses logical properties (`padding-inline-start`, `border-inline-end`, etc.) — never `left`/`right`
- Icons that imply direction (arrows, chevrons) mirror automatically in RTL
- Dark theme tokens documented for future use; not implemented in this refactor

## Colors

### Primary & Brand
- **Teal Primary** (`{colors.primary}` — #17786a): the single structural accent, reserved for primary CTAs, active states, and focus signals. This is the only colour that signals a primary action or interaction state.
- **Primary at 50%** (`{colors.primary-50pct}` — #abb0ad): used for disabled states or secondary visual hierarchy within a primary context.
- **On Primary** (`{colors.on-primary}` — #ffffff): white text/icons on the teal primary.

### Neutrals (Light)
- **Canvas** (`{colors.canvas}` — #f4f7f6): the page background; a subtle off-white that gives the interface calm and reduces clinical harshness.
- **Surface** (`{colors.surface}` — #ffffff): card backgrounds, input fields, and modal surfaces — pure white to create gentle figure/ground separation from the canvas.
- **Hairline** (`{colors.hairline}` — #e1e8e5): 1px borders on cards, form fields, table rows. The primary elevation cue in this flat system.
- **Ink** (`{colors.ink}` — #16201d): primary text, headings, and high-emphasis copy.
- **Ink Muted** (`{colors.ink-muted}` — #5c6b66): secondary body copy, labels, helper text, disabled states.
- **Table Header Background** (`{colors.table-header-bg}` — #eef3f1): header row background in data grids, distinct from body rows.
- **Zebra Row** (`{colors.zebra-row}` — #f8faf9): alternate row background in data tables to aid readability.

### Semantic Status (Light)
Each status has three values: `-deep` (text/icon on light backgrounds), `-bg` (tinted background for light badges/panels), and base (solid fills, dots, status indicators).

**Success** — Completion, approval, or affirmative state.
- Deep: `{colors.status-success-deep}` (#166534)
- Background: `{colors.status-success-bg}` (#e3f6ea)
- Solid: `{colors.status-success}` (#1f7a4d)

**Warning** — Caution, pending, or attention-required state.
- Deep: `{colors.status-warning-deep}` (#8a5a00)
- Background: `{colors.status-warning-bg}` (#fef3d6)
- Solid: `{colors.status-warning}` (#b87900)

**Danger** — Error, critical, or destruction-related state.
- Deep: `{colors.status-danger-deep}` (#9d2b1f)
- Background: `{colors.status-danger-bg}` (#fce4e1)
- Solid: `{colors.status-danger}` (#b23b30)

**Info** — Informational or neutral state (uses blue, NOT teal — teal is reserved for primary accent only).
- Deep: `{colors.status-info-deep}` (#1d4ed8)
- Background: `{colors.status-info-bg}` (#e3edfb)
- Solid: `{colors.status-info}` (#2563eb)

### Dark Theme (Reserved, Not Implemented)
The following dark tokens are documented for a future dark theme but are **not wired into any shipped theme** in this refactor. They are provided as reference values for when a dark mode is implemented.

**Dark Neutrals:**
- Canvas: `{colors.dark-canvas}` (#0e1613)
- Surface: `{colors.dark-surface}` (#182420)
- Ink: `{colors.dark-ink}` (#eaf1ee)
- Hairline: `{colors.dark-hairline}` (#28362f)
- Ink Muted: `{colors.dark-ink-muted}` (#93a49b)
- Table Header Background: `{colors.dark-table-header-bg}` (#1f2b26)
- Zebra Row: `{colors.dark-zebra-row}` (#141d1a)

**Dark Brand:**
- Primary: `{colors.dark-primary}` (#2dd4bf)
- App Drawer Gradient Start: `{colors.dark-drawer-start}` (#0a2e29)
- App Drawer Gradient End: `{colors.dark-drawer-end}` (#051917)

**Dark Semantic Status:**
- Success (deep/bg): `{colors.dark-status-success-deep}` (#4ade80) / `{colors.dark-status-success-bg}` (#0f2b1b)
- Warning (deep/bg): `{colors.dark-status-warning-deep}` (#fbbf24) / `{colors.dark-status-warning-bg}` (#332a10)
- Danger (deep/bg): `{colors.dark-status-danger-deep}` (#fb7185) / `{colors.dark-status-danger-bg}` (#331613)
- Info (deep/bg): `{colors.dark-status-info-deep}` (#60a5fa) / `{colors.dark-status-info-bg}` (#12233a)

## Typography

### Font Family
The entire system is set in **Cairo** — a typeface that renders Arabic and Latin seamlessly in both directions. Fallback stack: `"Cairo", "Segoe UI", Tahoma, sans-serif`. Cairo is self-hosted at weights **400**, **600**, and **700**.

### Hierarchy

| Token | Size | Weight | Line Height | Use |
|---|---|---|---|---|
| `page-title` | 28px | 700 | 1.3 | Page headline, panel titles |
| `section-title` | 20px | 700 | 1.4 | Section headers within a page |
| `card-title` | 16px | 600 | 1.4 | Card headers, modal titles |
| `card-title-numeric` | 16px | 600 | 1.4 | Same as `card-title`, tabular figures — use for money/score/count values |
| `body` | 14px | 400 | 1.5 | Default body copy, dense descriptions |
| `body-numeric` | 14px | 400 | 1.5 | Same as `body`, tabular figures — use for money/score/count values |
| `body-sm` | 13px | 400 | 1.4 | Table rows, form labels, helper text |
| `label` | 12px | 600 | 1.3 | Form labels, badge text, column headers |
| `caption` | 11px | 400 | 1.3 | Metadata, timestamps, supplementary notes |

### Principles
- **No negative letter-spacing:** Negative tracking degrades Arabic glyph joining and ligature rendering. All tokens are set at 0 letter-spacing.
- **Tabular numerals on numbers:** Use `body-numeric` / `card-title-numeric` (tabular figures via `font-feature-settings: 'tnum' 1`) instead of `body` / `card-title` when displaying money, scores, counts, or any numeric data, so digits align in columns.
- **Arabic-first hierarchy:** The type scale is conservative and data-oriented, not marketing-oriented. The largest size (28px) is sufficient for page titles in a dense back-office; there is no 64px display tier.
- **Latin text inside Arabic:** When Latin words or numbers appear inline within an Arabic paragraph, they naturally remain left-to-right; do **not** force-flip them to match the surrounding text direction.

## Layout

### Spacing System
- **Base unit**: 4px. All spacing tokens are multiples of 4.
- **Tokens**: `xs` 4px · `sm` 8px · `md` 12px · `lg` 16px · `xl` 24px · `xxl` 32px.
- Typical application:
  - Card interior padding: `lg` (16px)
  - Cell padding (data tables): `sm` block (8px) × `md` inline (12px)
  - Form field padding: `sm` (8px)
  - Gap between sections: `xl` (24px) or `xxl` (32px)

### Grid & Container
Content is arranged in a main container with consistent padding. The app drawer sits on the left (in LTR) or right (in RTL) as a fixed sidebar; the main content area expands to fill available width. Grids adapt to available space: data tables, form layouts, and card grids reflow logically using CSS Grid or Flexbox with logical properties.

### Whitespace Philosophy
Whitespace is the primary grouping device. Sections are separated by vertical gaps, and cards sit on the canvas with quiet hairlines instead of heavy frames. The flat, quiet aesthetic relies on clear breathing room rather than dividing lines or shadows.

## Elevation & Depth

Elevation in ClinicOS is **strictly hierarchical and minimal**.

- **Level 0 — Flat (Default):** 1px hairline border `{colors.hairline}` with no shadow. Used for all cards, form fields, and containers on the main canvas.
- **Level 1 — Modal Shadow (Reserved):** A barely-there shadow (`0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)`) applied only to modals, dialogs, and popover surfaces to lift them slightly off the background. No other component gets a shadow.

**Principle:** The hairline is the ONLY elevation cue for 99% of the interface. Shadow is reserved exclusively for modal/dialog scenarios to distinguish them as overlay surfaces. No floating cards, no layered micro-shadows, no decorative depth.

## Shapes

### Border Radius Scale
- **`xs` (2px):** Form fields, small inline elements.
- **`sm` (6px):** Nav items, status badges, small containers.
- **`md` (8px):** Card containers, buttons, modal dialogs.
- **`pill` (9999px):** Circular icon buttons (if needed for legacy icon-only controls); avoid for text buttons or modern UI.

All radii favour near-square corners per Carbon discipline. The maximum typical radius is `md` (8px) for card interiors.

## Lumo Mapping

This app's `theme.json` imports `typography, color, spacing, badge, utility` from Vaadin Lumo. Every token in this design system must resolve through the following mapping or Vaadin components will fight the theme.

| ClinicOS Token | Lumo Custom Property | Notes |
|---|---|---|
| `primary` (#17786a) | `--lumo-primary-color` | Primary accent for buttons, active states, focus rings |
| `primary` at 50% (#abb0ad) | `--lumo-primary-color-50pct` | Used for disabled/secondary contexts |
| `primary` (#17786a) | `--lumo-primary-text-color` | Primary-colored text (links, accent labels) — NOT text-on-primary-background; that's white and comes from `on-primary` directly wherever a component needs it |
| `canvas` (#f4f7f6) | `--lumo-tint-5pct` | Page/container background context |
| `surface` (#ffffff) | `--lumo-base-color` | Card and input surfaces |
| `hairline` (#e1e8e5) | `--lumo-shade-5pct` | Border and divider color |
| `ink` (#16201d) | `--lumo-body-text-color` | Primary text |
| `ink-muted` (#5c6b66) | `--lumo-secondary-text-color` | Secondary/muted text |
| `status-success-deep` | `--lumo-success-color-50pct` | Success text on light backgrounds |
| `status-warning-deep` | `--lumo-warning-color-50pct` | Warning text on light backgrounds |
| `status-danger-deep` | `--lumo-error-color-50pct` | Danger text on light backgrounds |

Lumo has no built-in "info" custom property — `status-info-*` tokens are used directly in ClinicOS's own `.clinicos-status-badge--info` class, not mapped onto Lumo. Do not repurpose a Lumo `error`/`warning`/`success` slot for info; that corrupts the component that actually owns it (e.g. a Vaadin error-state field background).

**Critical note:** Every Vaadin component (Button, TextField, Grid, Dialog, etc.) consumes these Lumo properties at render time. If a property is missing or mismatched, components fall back to Lumo defaults. The theme must be validated after every change to ensure no component is left using a hard-coded fallback.

## RTL (Right-to-Left)

ClinicOS is built for Arabic RTL environments. The design system enforces RTL correctness through CSS and React/Vaadin patterns.

### Rules (Non-Negotiable)
1. **Never use `left`, `right`, `margin-left`, `margin-right`, `padding-left`, `padding-right`** — always use logical properties:
   - `inset-inline-start` / `inset-inline-end` (for absolute positioning)
   - `padding-inline-start` / `padding-inline-end`
   - `margin-inline-start` / `margin-inline-end`
   - `border-inline-start` / `border-inline-end`
   - `margin-block-start` / `margin-block-end`
   - `padding-block-start` / `padding-block-end`
2. **Icons that imply direction must flip in RTL:** Arrows, chevrons, back/forward buttons, and any glyph that reads directionally must mirror automatically. Most icon libraries (Vaadin Icons, Material Icons) support RTL flipping via the `dir="rtl"` attribute on the `<html>` element.
3. **Latin text and numbers inside Arabic paragraphs remain LTR-embedded:** Do not force-flip or override the natural bidirectional text algorithm. If a price, date, or name appears mid-sentence in Arabic, it will naturally read left-to-right; this is correct and expected.
4. **Test all layouts with a `dir="rtl"` document root:** Every component and layout must be visually verified with `<html dir="rtl">` to catch logical-property mistakes, margin/padding errors, and icon-flip failures.

## Components

Each component is defined with its default state and typical variants. All use logical CSS properties; no `left`/`right` anywhere.

### App Shell

**`app-drawer`** — Dark sidebar navigation
- Background: dark teal gradient (`{colors.dark-drawer-start}` to `{colors.dark-drawer-end}`)
- Text: `{colors.dark-ink}` (light text on dark background)
- Width: 240px (fixed sidebar)
- Padding: `{spacing.lg}` (16px)
- Content: stacked `nav-item` elements; typically includes clinic/user menu at the top, nav items in the middle, and sign-out at the bottom

**`nav-item`** — Sidebar navigation row (default / hover / active)
- **Default:** Transparent background, muted text `{colors.dark-ink-muted}`, padding `{spacing.sm} {spacing.md}`, rounded `{rounded.sm}`
- **Hover:** Subtle white overlay at 5% opacity, text brightens to `{colors.dark-ink}`
- **Active:** Teal solid background `{colors.primary}`, white text, with a 3px left (LTR) / right (RTL) border-inline-start
- Typography: `{typography.body-sm}` (13px)

**`topbar`** — Top navigation bar
- Background: `{colors.surface}` (white)
- Text: `{colors.ink}` (dark)
- Border bottom: 1px solid `{colors.hairline}`
- Padding: `{spacing.md}` (12px) vertical × `{spacing.lg}` (16px) horizontal (using block/inline logical properties)
- Height: 56px (fixed, for consistent icon/avatar sizing)
- Typical content: clinic name, user menu, breadcrumbs (if needed)

### Page Layout

**`page-header`** — Section header container (kicker + title + optional subtitle)
- Padding: top `{spacing.lg}`, bottom `{spacing.md}`, sides `{spacing.lg}` (using logical properties)
- No border or background; sits directly on canvas

**`page-header-kicker`** — Optional eyebrow label above the title
- Typography: `{typography.label}` (12px / 600)
- Color: `{colors.ink-muted}`
- Margin below: `{spacing.xs}` (4px)

**`page-header-title`** — Main section headline
- Typography: `{typography.page-title}` (28px / 700)
- Color: `{colors.ink}`
- Margin below: `{spacing.sm}` (8px)

**`page-header-subtitle`** — Optional description under the title
- Typography: `{typography.body-sm}` (13px / 400)
- Color: `{colors.ink-muted}`

### Data Grid

**`data-grid`** — Table-like layout for lists and records
- Header row background: `{colors.table-header-bg}` (#eef3f1)
- Header typography: `{typography.label}` (12px / 600)
- Body row typography: `{typography.body-sm}` (13px / 400)
- Cell padding: `{spacing.sm}` (8px) block × `{spacing.md}` (12px) inline (using logical properties)
- Row divider: 1px solid `{colors.hairline}` between rows
- Zebra background: alternate rows at `{colors.zebra-row}` (#f8faf9) for readability
- Hover state: subtle teal overlay at ~5% opacity of `{colors.primary}`
- Note: Use logical properties (`padding-inline-start`, `margin-block-end`) for RTL safety

### Status & Scoring

**`status-badge`** — Status indicator (attendance, PO state, exam result, etc.)
- Padding: `{spacing.xs}` (4px) × `{spacing.sm}` (8px)
- Rounded: `{rounded.sm}` (6px)
- Typography: `{typography.label}` (12px / 600)
- Uses the semantic ramp; each status gets a `-bg` (light tinted background) and `-deep` (dark text) pair:
  - Success: bg `{colors.status-success-bg}`, text `{colors.status-success-deep}`
  - Warning: bg `{colors.status-warning-bg}`, text `{colors.status-warning-deep}`
  - Danger: bg `{colors.status-danger-bg}`, text `{colors.status-danger-deep}`
  - Info: bg `{colors.status-info-bg}`, text `{colors.status-info-deep}`

**`score-badge`** — Performance or evaluation score display (UC-004/UC-005)
- Padding: `{spacing.xs}` (4px) × `{spacing.sm}` (8px)
- Rounded: `{rounded.sm}` (6px)
- Typography: `{typography.label}` (12px / 600)
- Typography: `{typography.label-numeric}` (12px / 600, tabular figures) so numeric scores align consistently
- **Score mapping** (uses semantic ramp, not a separate color family):
  - Score ≥85: Success styling (bg `{colors.status-success-bg}`, text `{colors.status-success-deep}`)
  - Score 60–84: Warning styling (bg `{colors.status-warning-bg}`, text `{colors.status-warning-deep}`)
  - Score <60: Danger styling (bg `{colors.status-danger-bg}`, text `{colors.status-danger-deep}`)

### Forms

**`form-field`** — Text input, number field, dropdown, textarea, etc.
- Background: `{colors.surface}` (white)
- Text: `{colors.ink}` (dark)
- Border: 1px solid `{colors.hairline}`
- Rounded: `{rounded.sm}` (6px)
- Padding: `{spacing.sm}` (8px) (using logical padding-inline/padding-block)
- Typography: `{typography.body}` (14px / 400)
- **Focus state:** 3px teal outline (0 0 0 3px rgba of `{colors.primary}` at 10% opacity)
- **Placeholder text:** `{colors.ink-muted}`
- **Disabled state:** bg `{colors.canvas}`, text `{colors.ink-muted}`, cursor not-allowed

**`form-card`** — Container for a form (sign-in, settings, etc.)
- Background: `{colors.surface}` (white)
- Border: 1px solid `{colors.hairline}`
- Rounded: `{rounded.md}` (8px)
- Padding: `{spacing.lg}` (16px)
- Gap between form fields: `{spacing.md}` (12px) (using gap property if Flexbox/Grid)

### Buttons

**`button-primary`** — Primary action (submit, create, save, etc.)
- Background: `{colors.primary}` (#17786a, teal)
- Text: `{colors.on-primary}` (white)
- Rounded: `{rounded.sm}` (6px)
- Padding: `{spacing.sm}` (8px) block × `{spacing.lg}` (16px) inline
- Typography: `{typography.body-sm}` (13px / 600)
- Border: none
- Cursor: pointer
- **Hover:** background `#146c5f` (~10% darker)
- **Active/Pressed:** background `#136659` (~15% darker)
- **Disabled:** background `{colors.primary-50pct}`, text `{colors.ink}` (not white — fails contrast on the muted fill), cursor not-allowed

**`button-secondary`** — Secondary action (cancel, close, reset)
- Background: `{colors.surface}` (white)
- Text: `{colors.ink}` (dark)
- Border: 1px solid `{colors.hairline}`
- Rounded: `{rounded.sm}` (6px)
- Padding: same as primary
- Typography: same as primary
- Cursor: pointer
- **Hover:** background tints to `{colors.canvas}`
- **Disabled:** text `{colors.ink-muted}`, cursor not-allowed

**`button-danger`** — Destructive action (delete, remove, abandon)
- Background: `{colors.status-danger}` (#b23b30, red)
- Text: `{colors.on-primary}` (white)
- Rounded: `{rounded.sm}` (6px)
- Padding: same as primary
- Typography: same as primary
- Border: none
- Cursor: pointer
- **Hover:** background `#a03528` (~10% darker)
- **Disabled:** background `{colors.primary-50pct}`, text `{colors.ink}` (not white — fails contrast on the muted fill), cursor not-allowed

### Dialogs & Modals

**`dialog`** — Confirmation, form, or content modal
- Background: `{colors.surface}` (white)
- Text: `{colors.ink}`
- Border: 1px solid `{colors.hairline}`
- Rounded: `{rounded.md}` (8px)
- Padding: `{spacing.lg}` (16px)
- Shadow: `0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)` (barely-there modal shadow; the ONLY shadow in the system)
- Minimum width: 400px (on desktop; reflow to 100% on mobile minus padding)
- Title typography: `{typography.card-title}` (16px / 600)
- Body typography: `{typography.body}` (14px / 400)
- **Always sits on top** using a backdrop layer (typically with a semi-transparent background `rgba(0,0,0,0.3)`)

**`toast`** — Transient notification (success, error, info)
- Background: `{colors.surface}` (white)
- Text: `{colors.ink}`
- Border: 1px solid `{colors.hairline}`
- Rounded: `{rounded.md}` (8px)
- Padding: `{spacing.md}` (12px) block × `{spacing.lg}` (16px) inline
- Shadow: `0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04)`
- Typography: `{typography.body-sm}` (13px / 400)
- Position: typically bottom-right (LTR) / bottom-left (RTL) with safe margins from viewport edge
- Auto-dismiss after 4–5 seconds unless user interacts

### Empty States

**`empty-state`** — No data / no results view
- Container padding: `{spacing.xxl}` (32px) on all sides
- Text alignment: center
- Composed of three children:
  1. Icon (64px, color `{colors.ink-muted}`)
  2. Heading (optional, typography `{typography.card-title}`, color `{colors.ink}`)
  3. Message (typography `{typography.body}`, color `{colors.ink-muted}`)

---

## Do's and Don'ts

### Do

- **Colour:** Reserve `{colors.primary}` (teal) exclusively for primary actions, active states, and focus signals. Use the semantic ramp (success, warning, danger, info) for all status and feedback — never invent a new colour outside this token set for any future view or feature.
- **Elevation:** Use 1px hairlines as the default elevation cue. Reserve the modal shadow for dialogs and popovers only; never shadow a card, button, or regular container.
- **Typography:** Always use Cairo at weights 400, 600, or 700. Use `body-numeric` / `card-title-numeric` for money, scores, or counts so numbers align vertically.
- **RTL:** Use only logical CSS properties (`padding-inline-start`, `margin-block-end`, `border-inline-start`, etc.). Test every layout with `dir="rtl"` to verify correctness.
- **Icons:** Ensure direction-implying icons (arrows, chevrons, back/forward) flip automatically in RTL. Validate in both LTR and RTL rendering.
- **Arabic + Latin:** When Latin words or numbers appear inline in Arabic text, allow them to render left-to-right naturally; do not force-flip them.
- **Form fields:** Use `{rounded.sm}` (6px) for inputs and form fields. On focus, apply a teal outline shadow, not a thick border.
- **Buttons:** Use `{rounded.sm}` for all buttons. Pair `button-primary` (teal) with `button-secondary` (white border) for binary choices; use `button-danger` only for destructive actions.
- **Data tables:** Alternate row backgrounds with `{colors.zebra-row}` to improve readability. Add a subtle teal hover overlay to each row for interactive feedback.
- **Empty states:** Always include an icon, optional heading, and explanatory message. Never leave a user staring at blank space without context.

### Don't

- **Don't introduce a new colour outside the token set.** Every colour in the system is defined in the front-matter. If a new status or semantic meaning is needed, reuse existing tokens or request a new addition to this design system (do not invent ad-hoc colours in component code).
- **Don't use `left`, `right`, `margin-left`, `margin-right`, `padding-left`, or `padding-right` anywhere in the codebase.** Always use logical properties. Violations break RTL layouts.
- **Don't use negative letter-spacing on type.** Negative tracking breaks Arabic glyph joining and ligatures. All typography is set at `letter-spacing: 0`.
- **Don't shadow non-modal containers.** Shadows are reserved exclusively for dialogs, popovers, and overlay surfaces. Cards, buttons, and regular UI elements use hairlines only.
- **Don't force-flip Latin numbers or names inside Arabic text.** The bidirectional algorithm handles this correctly; overriding it breaks readability.
- **Don't round buttons more than `{rounded.sm}` (6px).** Pill-shaped buttons (9999px) are not used in this operate-mode design; buttons stay near-square per Carbon discipline.
- **Don't mix rounded values arbitrarily.** Use only `xs` (2px), `sm` (6px), `md` (8px), or `pill` (9999px). Inventing intermediate values like 10px or 14px breaks visual consistency.
- **Don't use the dark theme tokens in shipped UI.** They are reserved for a future dark mode and documented only for reference. The current system is light-mode only.
- **Don't put decorative illustrations or sticker palettes in the UI.** This is a data-dense clinic tool, not a marketing site. All graphics serve functional purposes: icons, avatars, charts, and instructional diagrams only.
