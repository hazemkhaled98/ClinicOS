---
name: new-screen
description: Checklist for building or changing a Thymeleaf screen in ClinicOS — reference design assets, reuse component classes, extend DESIGN.md/tokens when needed, and run hygiene tests. Use when implementing or modifying a UI screen/view.
---

1. Read `ClinicOS Design/<NN>_*/screen.png` for the visual target and `code.html` for layout/information-architecture/Arabic copy. Ignore its `tailwind.config`, fonts, hex values, and physical-direction utilities — those are never carried into a template.
2. Compose the screen from `apps/api/src/main/styles/components.css` classes. Check `/dev/styleguide` first to see what already exists before writing new markup.
3. Need something not covered by an existing component class or token? Extend `DESIGN.md` first, then `tokens.css`/`components.css`, then add it to the styleguide page — never hardcode a one-off value in a template.
4. Run `npm run build:css` (from `apps/api`), then `mvn test -Dtest=TemplateHygieneTest,CssHygieneTest` before considering the screen done.

Templates live in `apps/api/src/main/resources/templates/`; controllers in `com.clinicos.ui` map routes to them and prepopulate a `LayoutModel` (drawer nav from the session's primed permissions/role). Interactive server round-trips use HTMX (`hx-*` attributes) with a `th:attr`-built `hx-headers` carrying the CSRF token; light client state uses Alpine.js.
