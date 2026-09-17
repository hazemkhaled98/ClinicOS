---
name: pr-sentinel
description: PR-scoped review workflow for an already-open pull request, run via /pr-sentinel. On re-runs, reviews only changes since the last review round (incremental). Runs a design-council pass on the UI changes (applies visual polish, proposes motion), then ponytail-review (over-engineering), then pr-review-toolkit's review-pr (correctness/quality) and offers a choice of executor — Claude in-session, opencode in a separate terminal, or report only — before applying and committing confirmed fixes. Use this whenever the user wants a PR reviewed, asks "review this PR", or runs "run the PR review".
---

# pr-sentinel

One review pass, scoped to an open PR: everything the PR is about to ship,
reviewed through two lenses back to back — over-engineering first, then
correctness/quality — with fixes committed as you approve them.

Security scanning (Strix) isn't part of this flow. Run `strix-pentest`
separately if a security pass is wanted.

Invoke it directly any time a PR is open.

## Preconditions

The current branch must have an open PR. Check with:

```
gh pr view --json number,state,baseRefName,headRefName
```

If there's no open PR, tell the user and stop — this skill reviews PRs, not
arbitrary diffs. (For a pre-commit sanity check before a PR exists, that's a
different, narrower need — say so rather than trying to repurpose this flow.)

## State tracking

To avoid re-reviewing already-reviewed code on subsequent runs, this skill
persists the last-reviewed commit per PR in `.claude/pr-sentinel-state.json`:

```json
{ "42": "abc1234def5678", "87": "aabbccdd1122" }
```

Keys are PR numbers. Values are full commit SHAs of HEAD at review completion.

- **Read** before diffing (see Flow below) to determine first-run vs re-run.
- **Write** after the fix phase resolves and before the report (see Flow item 4).
- **Cleanup**: when a PR is closed/merged, remove its entry — harmless if
  left in place.

## Flow

Before diffing, update the local base ref so the review only covers what the
PR actually adds, not stuff master already merged: `git fetch origin
<base>:<base>` (or `git pull origin <base>` if `<base>` is checked out
elsewhere) where `<base>` is `baseRefName` from `gh pr view`. Skip if the
fetch/pull fails (e.g. offline) — just note it in the report and diff against
whatever base ref is already local.

**Determine the diff range:**

1. Read `.claude/pr-sentinel-state.json`. Look up the current PR number.
2. **First run** (no entry for this PR): diff range is `<base>...HEAD`
   — full PR diff, same as before.
3. **Re-run** (entry exists): check if the stored commit is reachable from
   HEAD with `git merge-base --is-ancestor <stored_commit> HEAD`.
   - **Reachable**: diff range is `<stored_commit>...HEAD` — incremental,
     only new changes since last review.
   - **Not reachable** (force push, rebase, branch reset): fall back to
     `<base>...HEAD` and note in the report that a full diff was used
     because the previous review point was lost.
4. After the fix phase resolves — Step 3's Claude/opencode/report-only branch
   is done (for opencode, after the wake and commit) and Step 4's report is
   about to be written — update the state file: set the current PR's entry to
   the full SHA of HEAD (`git rev-parse HEAD`). Create
   `.claude/pr-sentinel-state.json` if it doesn't exist.

### Step 1 -- design-council frontend pass

1. From the diff range determined above (do not recompute), list the changed
   files: `git diff --name-only <range>`.
2. Filter that list to UI files using design-council's UI-file gate (see
   design-council's "PR Review Mode"). If nothing survives the gate, state
   "no frontend changes in this diff" and skip straight to Step 2 -- no empty
   report, no council run.
3. Otherwise invoke `design-council` in PR Review Mode, passing the diff
   range and the UI file list; design-council pulls the hunks itself from
   that range. Its visual-polish pass (Phase 1-2) asks for approval, applies
   with `impeccable polish` scoped to files the diff touches, and commits
   that pass on its own -- this step is not report-only for visual findings.
   Its motion pass (Phase 3-4) stays unbuilt and comes back as proposals.
4. Show design-council's output as two labeled blocks: what it *applied*
   (with its own commit SHA) and what it *proposes* for motion -- clearly
   labeled as the design-council pass so they can't be mistaken for ponytail
   or review-pr output.
5. The proposed-motion findings ride into Step 3's fix-selection flow -- the
   same single `AskUserQuestion` covers review-pr's findings and
   design-council's motion proposals together. If review-pr's list ends up
   empty but design-council's doesn't, still ask about design-council's
   findings alone.
6. Approved motion findings are built with `animate` (under
   `emil-design-eng` principles) via the same Claude / opencode /
   report-only branches Step 3 documents, landing in review-pr's commit. The
   visual-polish commit from step 3 above is separate and already made by
   then -- a `/pr-sentinel` run can land two commits from this pass alone.

### Step 2 — ponytail pass

1. Invoke the `ponytail:ponytail-review` skill against that PR diff. It
   reports over-engineering findings only — it does not apply fixes itself.
2. Show the findings to the user and ask for approval before touching
   anything (findings can be wrong, and this is a shared PR, not a scratch
   diff — get a yes before editing).
3. Once approved, apply each fix directly with Edit on the working-tree
   files, `git add` exactly what you changed, and create one commit for this
   pass. Don't touch anything outside what the findings named, and don't
   revert or discard work that isn't itself a finding.
4. If ponytail-review found nothing, say so and move straight to Step 3 —
   no empty commit.

### Step 3 — PR-toolkit pass

1. Invoke the `pr-review-toolkit:review-pr` skill, scoped to the same PR
   (the diff range determined above) — it runs the full multi-agent pass (code-reviewer,
   comment-analyzer, silent-failure-hunter, and whichever others apply to
   what changed). This lens is different on purpose: real bugs and quality
   problems in logic that already exists, not the ponytail lens. Report only
   at this point — don't apply anything yet.
2. Aggregate every confirmed, safely-fixable finding -- from review-pr AND
   from the design-council pass -- into one numbered list and show it to the
   user. Findings you're not confident enough to fix safely don't go in this
   list -- name them in the Step 4 summary instead.
3. If the list is empty, say so and go straight to Step 4 — no prompt needed.
   Otherwise ask **one** `AskUserQuestion`, header `"Fixes"`, question "Who
   should apply these fixes?":
   - `"Claude fixes them (in-session)"`
   - `"opencode fixes them (separate terminal)"`
   - `"Don't fix — report only"`
4. **Claude branch:** apply each fix directly with Edit, `git add` exactly
   the files you changed, and create ONE new commit for this pass's fixes --
   review-pr's and design-council's fixes both land in this same commit.
   Never amend an existing commit or rewrite history that might already be
   shared.
5. **opencode branch:** write the numbered findings list to a `brief.md` file
   under a fresh run dir (e.g. `%TEMP%/claude-opencode/<session>-<ts>/`),
   prefixed with the standing delegate style block (ponytail ultra + caveman
   ultra — see `~/.claude/hooks/delegate-dispatch.mjs`'s `DELEGATE_STYLE` for
   the exact wording) and an explicit "do NOT commit/stage/push — leave
   unstaged" instruction. Then start exactly one `run_in_background` Bash
   call:
   ```
   node ~/.agents/skills/opencode-delegate/scripts/relay.mjs \
     --brief <run-dir>/brief.md --lane review-fixes --cd <repo root> \
     --out-dir <run-dir> --timeout 30m
   ```
   Don't poll — the harness re-invokes you when it exits. If the brief is
   empty the relay exits 2 before running; fall back to the Claude branch
   instead of stalling.
   **On wake:** read `<run-dir>/result.json` (status, finalMessage,
   touchedFiles) if present — the relay writes it on completed/failed/timeout,
   but a background shell wrapper can still finish with no result.json on a
   pre-run usage error, so fall back to `git status`/`git diff` regardless.
   Either way, treat the working tree as source of truth: run `git diff` and
   `git status` — opencode leaves everything unstaged by design. Verify the
   changes match the findings list and nothing extra crept in, stage only
   those files, and make ONE commit. Then continue to Step 4.
6. **Report-only branch:** skip straight to Step 4 — no edits, no commit.

### Step 4 — report

One summary covering all passes: what design-council applied as visual
polish (and its commit, if separate) plus what it proposed for motion, what
ponytail cut (or "already lean"), what review-pr found and fixed, and
anything left unfixed with why. State plainly that this pass may have
produced more than one commit — design-council's own polish commit, then the
shared ponytail/review-pr/motion commit. Include the diff range used (full
or incremental) so the user knows what was covered. Don't push — that stays
a separate, explicit action the user takes next.

