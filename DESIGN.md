---
name: ClinicOS
colors:
  surface: '#f6faf8'
  surface-dim: '#d5dcd9'
  surface-bright: '#f6faf8'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eef5f2'
  surface-container: '#e8efec'
  surface-container-high: '#e3eae7'
  surface-container-highest: '#dde4e1'
  on-surface: '#161d1b'
  on-surface-variant: '#414846'
  inverse-surface: '#2b3230'
  inverse-on-surface: '#ebf2ef'
  outline: '#717976'
  outline-variant: '#c1c8c5'
  surface-tint: '#43655e'
  primary: '#001814'
  on-primary: '#ffffff'
  primary-container: '#0a2e29'
  on-primary-container: '#749790'
  inverse-primary: '#aacec6'
  secondary: '#076c41'
  on-secondary: '#ffffff'
  secondary-container: '#9bf2bb'
  on-secondary-container: '#117145'
  tertiary: '#201100'
  on-tertiary: '#ffffff'
  tertiary-container: '#37250c'
  on-tertiary-container: '#a68b6a'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#c5eae2'
  primary-fixed-dim: '#aacec6'
  on-primary-fixed: '#00201c'
  on-primary-fixed-variant: '#2b4d47'
  secondary-fixed: '#9ef5be'
  secondary-fixed-dim: '#82d8a3'
  on-secondary-fixed: '#002110'
  on-secondary-fixed-variant: '#005230'
  tertiary-fixed: '#feddb8'
  tertiary-fixed-dim: '#e0c19d'
  on-tertiary-fixed: '#281803'
  on-tertiary-fixed-variant: '#584327'
  background: '#f6faf8'
  on-background: '#161d1b'
  surface-variant: '#dde4e1'
typography:
  display-lg:
    fontFamily: Noto Sans Arabic
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Noto Sans Arabic
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Noto Sans Arabic
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 26px
  title-md:
    fontFamily: Noto Sans Arabic
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Noto Sans Arabic
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
  body-md:
    fontFamily: Noto Sans Arabic
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
  label-md:
    fontFamily: Noto Sans Arabic
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
  label-xs:
    fontFamily: Noto Sans Arabic
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
  numeric-metric:
    fontFamily: Noto Sans Arabic
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.02em
rounded:
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  full: 9999px
---

## Brand & Style

The design system serves as an operational cockpit for clinical directors, clinic receptionists, and medical practitioners. It balances two fundamental psychological needs: the clinical precision required for back-office records, invoicing, and patient treatment logs, alongside motivational momentum required to retain and drive clinical staff performance. 

The aesthetic is grounded in **Precision Modernism with Micro-Gamification**:
- **Demeanor**: Authoritative, calm, hygienic, and rewarding. The environment feels clinical without feeling sterile or punitive.
- **RTL-Native Architecture**: Built from the ground up for Arabic-first clinic operations, maintaining semantic visual balance in Right-to-Left while gracefully inverting for Left-to-Right users.
- **Dual Visual Personality**: Data-dense grids, exact financial ledgers, and tabular control planes interface seamlessly with vibrant, low-friction visual tokens (streak counters, clinical velocity rings, achievement tiers, and performance pacing meters).

## Colors

The palette establishes an environment of surgical cleanliness and grounded permanence through deep botanical teals, cool canvas whites, and high-legibility semantic cues.

### Structural Foundations
- **Canvas Base (`#f6faf8`)**: A cool, low-strain backdrop that cuts the harsh glare of pure white monitor screens during long administrative shifts.
- **Surface (`#ffffff`)**: Pure white reserved for actionable surfaces, records, drawers, and modal sheets.
- **Structural Hairlines (`#e2e8f0`)**: 1px perimeter outlines establishing boundaries without heavy cognitive load.
- **Primary Ink (`#0a2e29`)**: A near-black, deep surgical teal used for primary brand elements, dominant headings, and high-impact navigational anchors.

### Semantic Tiers
- **Success & Goal Completion**: Base `#1f7a4d` on soft tint `#e8f5ed`. Applied to treatment completions, collected receivables, and performance badges.
- **Warning & Pending Status**: Base `#b87900` on soft tint `#fef7e6`. Applied to overdue recalls, expiring insurance pre-authorizations, and inventory low-stock limits.
- **Critical & Emergency**: Base `#b23b30` on soft tint `#fdf2f2`. Reserved strictly for medical alerts, severe billing disputes, system errors, and canceled procedures.
- **Informational & Scheduling**: Base `#2563eb` on soft tint `#eff6ff`. Denotes upcoming appointments, doctor reassignments, and system audit trails.

## Typography

Typography prioritizes Arabic-first visual balance and compact operational density. Headings and operational body copy maintain strict optical alignments with baseline metrics.

- **Font Stack**: **Noto Sans Arabic** carries all text (weights 300–800), with **Inter** (weights 400–700) as the Latin companion glyph set. Configure via Tailwind `fontFamily.sans = ['Noto Sans Arabic', 'Inter', 'sans-serif']`; both fonts are self-hosted.
- **Numerics**: Monospaced tabular figures (`font-variant-numeric: tabular-nums`) must be active for all tables, price fields, dental tooth notations, patient IDs, and KPI counters.
- **RTL Fluidity**: When switching from Arabic to English, line heights remain strictly fixed at their defined pixel values to eliminate horizontal row jumping or baseline shifting across bilingual inputs.
- **Weight Pairing**: Restrict weights to 400 (Regular), 500 (Medium), 600 (SemiBold), and 700 (Bold). Avoid extra-thin or ultra-heavy weights that render inconsistently on clinical monitors.

## Layout & Spacing

The layout is built around a rigorous 4px baseline grid tailored for high-density administrative software.

### Canvas Grid & Breakpoints
- **Master Shell**: Fixed vertical navigation on the functional start edge (Right in RTL, Left in LTR) with a width of `260px`. The main workspace flows dynamically into the remaining viewport.
- **Container Structure**: Fluid horizontal presentation with a minimum width cutoff at `1024px`. Sub-screens reflow linearly below `768px` for tablet-based dental chairside assistance.
- **Density Controls**:
  - **Dense (Back-Office/Ledger)**: Compact padding (`8px` horizontal, `4px` vertical) and row heights locked at `36px` to maximize data visibility without paging.
  - **Comfortable (Clinical Overview/Staff Arena)**: Cell padding (`12px` horizontal, `8px` vertical) and row heights of `44px`.

## Elevation & Depth

The system layers surfaces with a combination of crisp structural borders, layered shadow depths, decorative ambient glow, and glass-morphism accents:

- **Layered Shadow System**: Subtle shadows on data tables and cards (Level 1–2), heavier `shadow-2xl` on auth/modal surfaces (Level 3) for visual dominance and focus.
- **Decorative Ambient Glow**: Purely decorative, non-interactive blurred orbs (`blur-3xl`, low-opacity brand-color circles, positioned `absolute`) sit behind hero panels on dark backgrounds, adding visual warmth without cognitive load.
- **Glass-Morphism Cards**: Feature highlight tiles on light backgrounds use a layered glass effect — semi-transparent white background (`rgba(255, 255, 255, 0.7–0.85)`), thin brand-tinted border, and `backdrop-filter: blur(12px)` for subtle depth.
- **Z-Index Layering**:
  - `Level 0 (Canvas)`: Background canvas `#f6faf8`.
  - `Level 1 (Card/Table Surface)`: Background `#ffffff`, border 1px solid `#e2e8f0`, no drop shadow.
  - `Level 2 (Popovers & Quick Lookups)`: Background `#ffffff`, border 1px solid `#e2e8f0`, shadow `0 4px 12px rgba(10, 46, 41, 0.06)`.
  - `Level 3 (Modals, Auth Cards & Flyout Drawers)`: Background `#ffffff` (or glass), border 1px, shadow `0 12px 32px rgba(10, 46, 41, 0.12)` (or heavier `shadow-2xl` for auth prominence).
- **Active Focus & Row Highlighting**: Instead of elevating hover items with shadows, rows and actionable cards highlight via an internal tint fill (`#0a2e29` at 3% opacity) combined with an edge accent line.

## Shapes

The design uses moderate corner radii to project approachability and visual refinement while maintaining structural clarity:

- **Small Radius (8px, `rounded-sm`)**: Applied to icon containers and micro-elements.
- **Standard Radius (12px, `rounded-md`)**: Applied to text input fields, operational buttons, card shells, navigation items, form controls, and dropdowns.
- **Large Radius (16px, `rounded-lg`)**: Applied to feature highlight tiles and grouped card sections.
- **Extra-Large Radius (24px, `rounded-xl`)**: Applied to the primary auth card and drawer feature sections for visual prominence.
- **Pill Exception (9999px)**: Strictly reserved for status badges, gamified performance streak bubbles, motivational metric pills, and active user avatars. Never apply pill radii to structural containers, inputs, or operational buttons.

## Components

### Buttons
- **Primary Action**: Background `#0a2e29`, text `#ffffff`, border radius `12px`. Auth/marketing screens: height `52px` (py-3.5 + sm text); in-app dense controls may use smaller heights. Hover state darkens toward `#051a17`; active state engages a 1px inner inset ring.
- **Secondary Action**: Background `#ffffff`, border 1px solid `#e2e8f0`, text `#0a2e29`. Hover applies background `#f6faf8`.
- **Destructive Action**: Background `#fdf2f2`, border 1px solid `#b23b30`, text `#b23b30`.

### Data Grids & Tabular Views
- **Header**: Background `#f4f7f6`, text color `#0a2e29` (70% opacity), font size `11px`, font weight `600`, border-bottom 1px solid `#e1e8e5`.
- **Rows**: Alternating row hover fill (`#f9fbfb`). Row-level action buttons remain visible on hover or persistent via three-dot context triggers.
- **Cell Alignment**: Text content follows layout alignment (right-aligned for Arabic). Numerical metrics, monetary values, and phone numbers are strictly left-aligned or positioned with fixed-width tabular formatting.

### Input Fields & Controls
- **Input Housing**: Background `#ffffff`, border 1px solid `#e2e8f0`, radius `12px`. Auth/marketing screens: height `52px` (py-3.5 + sm text), font size `14px`; in-app dense inputs may use smaller heights.
- **State Behavior**: Focus state applies an active border of `1.5px solid #0a2e29` with zero ambient outer glow. Error state switches the border to `#b23b30`.
- **Labels**: Positioned persistently above inputs (`font-size: 12px`, weight `600`, color `#0a2e29`).

### Gamification & Staff Performance Modules
- **Velocity Rings**: SVG-based concentric progress rings for daily target treatments and hygiene recalls. Track width `4px`, track background `#e1e8e5`, active stroke `#1f7a4d` (or `#b87900` when behind schedule).
- **Streak Badges**: Pill-shaped container (`border-radius: 9999px`), background `#fef7e6`, border 1px solid `#b87900`, displaying consecutive clinic operational days or positive review runs with an embedded icon.
- **Daily Inspiration Banner**: Compact single-line banner at top of staff views with a light tint fill (`#eff6ff`), subtle 1px border (`#2563eb`), and 12px typography to build positive momentum.

### Chips & Semantic Status Badges
- **Dimensions**: Height `22px`, horizontal padding `8px`, corner radius `9999px`, font size `11px`, weight `600`.
- **Semantic Pairing**:
  - Confirmed / Paid: Background `#e8f5ed`, text `#1f7a4d`.
  - Pending / In-Chair: Background `#fef7e6`, text `#b87900`.
  - Cancelled / Overdue: Background `#fdf2f2`, text `#b23b30`.
  - Scheduled: Background `#eff6ff`, text `#2563eb`.

### Brand Color Palette (Tailwind Utilities)

The following brand colors are configured as Tailwind utilities in `apps/api/src/main/styles/tokens.css` via a `@theme` block. Use these utility names (`bg-teal-900`, `text-emerald-500`, `border-teal-850`) rather than raw hex or arbitrary values (`bg-[#...]`):

- **Teal Scale**: teal-950 `#071f1c`, teal-900 `#0a2e29`, teal-850 `#0d3832`, teal-800 `#114a42`, teal-700 `#17655a`, teal-600 `#1f8375`, teal-500 `#2a9d8f`, teal-300 `#5eead4`, teal-100 `#ccfbf1`
- **Emerald Scale** (full, stock Tailwind emerald adopted as brand): emerald-800 `#065f46`, emerald-700 `#047857`, emerald-600 `#059669`, emerald-500 `#10b981`, emerald-400 `#34d399`, emerald-300 `#6ee7b7`, emerald-200 `#a7f3d0`, emerald-100 `#d1fae5`, emerald-50 `#ecfdf5`
- **Neutral Scale** (stock Tailwind slate adopted as the named neutral ramp; matches the Structural Hairlines anchor `#e2e8f0` at step 200): neutral-900 `#0f172a`, neutral-800 `#1e293b`, neutral-700 `#334155`, neutral-600 `#475569`, neutral-500 `#64748b`, neutral-400 `#94a3b8`, neutral-300 `#cbd5e1`, neutral-200 `#e2e8f0`, neutral-100 `#f1f5f9`, neutral-50 `#f8fafc`
- **Danger Scale** (the Critical & Emergency tier's own two-tone system — base + tint, not a full ramp): danger-600 `#b23b30`, danger-50 `#fdf2f2`

Error text and error-state borders use the `danger-600` base, with soft tint surfaces (alerts, destructive-action chips) on `danger-50`. Do not use Tailwind's stock `red-*` steps; reach for `danger-*` instead. Same rule for gray: `neutral-*`, never `slate-*`.

These are the canonical colors for all UI elements; hex values used in Stitch comps must be translated to their utility equivalents before implementation.