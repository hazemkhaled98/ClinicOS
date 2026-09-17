---
name: pr-sentinel
description: PR-scoped review workflow for an open pull request on the remote, run via /pr-sentinel. Resolves the PR from origin (explicit number/URL/branch, the current branch's PR, or the sole open PR) and runs every pass in the PR head's git worktree — creating one if none holds the branch — independent of the worktree this session launched in. On re-runs, reviews only changes since the last review round (incremental). Runs a design-council pass on the UI changes (applies visual polish, proposes motion), then ponytail-review (over-engineering), then pr-review-toolkit's review-pr (correctness/quality) and offers a choice — apply the confirmed fixes in-session, or report only — before applying and committing confirmed fixes. Use this whenever the user wants a PR reviewed, asks "review this PR", or runs "run the PR review".
---

# pr-sentinel

One review pass, scoped to an open PR: everything the PR is about to ship,
reviewed through two lenses back to back — over-engineering first, then
correctness/quality — with fixes committed as you approve them.

Security scanning (Strix) isn't part of this flow. Run `strix-pentest`
separately if a security pass is wanted.

Invoke it any time a PR is open — optionally with a target (`/pr-sentinel 18`,
a PR URL, or a head branch); with no target it resolves the open PR itself
(see Preconditions).

## Preconditions

Reference is the **open PR on `origin`**, independent of which branch or
worktree this session was launched in. Resolve the target first:

```
# explicit target wins: PR number, URL, or head branch name
gh pr view <target> --json number,state,baseRefName,headRefName,url

# no target, but this worktree's branch has an open PR
gh pr view --json number,state,baseRefName,headRefName,url

# otherwise list the remote's open PRs
gh pr list --state open --json number,headRefName,baseRefName,title
```

- Explicit target -> use it.
- No target, current branch has a PR -> use it.
- Otherwise: exactly one open PR -> use it; more than one -> ask which with
  one `AskUserQuestion`; none -> tell the user and stop.
- The PR must be `OPEN`. If merged/closed, say so and stop.

This skill reviews PRs, not arbitrary diffs. For a pre-commit sanity check
before a PR exists, that's a different, narrower need — say so rather than
trying to repurpose this flow.

## PR worktree

Every command and edit runs in the PR head's worktree, **not** necessarily the
one this session was launched in. From the PR JSON: `<head>` = `headRefName`,
`<base>` = `baseRefName`, `<N>` = `number`.

1. **Find it.** `git worktree list --porcelain`. Match the worktree whose
   `branch` is `refs/heads/<head>`. A detached worktree whose `HEAD` equals
   the PR head SHA (`git ls-remote origin refs/heads/<head>`) also counts.
   That path is `$PR_WORKTREE`.
2. **Prefer a branch over a detached HEAD.** If the only match is detached,
   attach it: `git -C "$PR_WORKTREE" switch <head>`. If git refuses because
   the branch is checked out in another worktree, use that worktree instead.
3. **Create one if no worktree holds the branch:**
   ```
   git fetch origin <base>
   git fetch origin refs/heads/<head>:refs/heads/<head>   # local branch from remote head
   git worktree add "<parent-of-main-root>/<repo>-pr<N>" <head>
   ```
   (`<repo>` = the repo directory name; `<parent-of-main-root>` = the
   directory containing the main worktree. The refspec fetch refuses if
   `<head>` is checked out somewhere — step 1 already returned that worktree.)
4. **Run everything there.** Prefix git with `git -C "$PR_WORKTREE"` and
   resolve file paths (Edit, and design-council/ponytail/review-pr targets)
   relative to `$PR_WORKTREE`. Never commit or edit in the launch worktree.
   If `$PR_WORKTREE/.agents` is absent (a worktree predating the tracked
   skills, or a PR branch behind the commit that added them), don't reference
   `.agents/...` relative to that cwd — invoke skill tooling (impeccable's
   launcher, design-council, etc.) by its absolute path under the main
   worktree, e.g.
   `<main-worktree>/.agents/skills/impeccable/scripts/impeccable.cmd context`.

The **main** worktree (first `worktree` entry in `git worktree list
--porcelain`) holds the shared state file, so runs from any worktree see the
same review history.

## State tracking

To avoid re-reviewing already-reviewed code on subsequent runs, this skill
persists the last-reviewed commit per PR in the **main worktree's**
`.agents/pr-sentinel-state.json` (not the PR worktree's — see "PR worktree"):

```json
{ "42": "abc1234def5678", "87": "aabbccdd1122" }
```

Keys are PR numbers. Values are full commit SHAs of HEAD at review completion.

- **Read** before diffing (see Flow below) to determine first-run vs re-run.
- **Write** after the fix phase resolves and before the report (see Flow item 4).
- **Cleanup**: when a PR is closed/merged, remove its entry — harmless if
  left in place.

## Flow

Before diffing, sync the base from the remote in the PR worktree so the
review only covers what the PR actually adds: `git -C "$PR_WORKTREE" fetch
origin <base> <head>`. The diff range's base endpoint is `origin/<base>` (the
remote is the reference) — never a local `<base>` that may be stale or checked
out in another worktree. If `origin/<head>` is ahead of local HEAD and you
have no unreviewed local commits, fast-forward first: `git -C "$PR_WORKTREE"
merge --ff-only origin/<head>`. Skip the fetch if it fails (e.g. offline), note
it in the report, and diff against whatever `origin/<base>` is already local.

**Strict order, no collapsing.** The three steps run 1 -> 2 -> 3, each one
completing before the next starts. Step 1 (design-council) is *not* report-only:
when it finds visual defects it asks for approval, applies them, and commits
before Step 2 begins. Steps 2 and 3 each ask for approval before editing. Never
run the passes together as a single report-only sweep — the design-council pass
still has to run and land its fixes.

**Determine the diff range:**

1. Read `<main-worktree>/.agents/pr-sentinel-state.json`. Look up the current
   PR number.
2. **First run** (no entry for this PR): diff range is `origin/<base>...HEAD`
   — full PR diff, same as before.
3. **Re-run** (entry exists): check if the stored commit is reachable from
   HEAD with `git -C "$PR_WORKTREE" merge-base --is-ancestor <stored_commit>
   HEAD`.
   - **Reachable**: diff range is `<stored_commit>...HEAD` — incremental,
     only new changes since last review.
   - **Not reachable** (force push, rebase, branch reset): fall back to
     `origin/<base>...HEAD` and note in the report that a full diff was used
     because the previous review point was lost.
4. After the fix phase resolves — Step 3's apply flow is done (fixes committed)
   and Step 4's report is about to be written — update the state file: set the
   current PR's entry to the full SHA of HEAD (`git -C "$PR_WORKTREE" rev-parse
   HEAD`). Create `<main-worktree>/.agents/pr-sentinel-state.json` if it
   doesn't exist.

### Step 1 -- design-council frontend pass

1. From the diff range determined above (do not recompute), list the changed
   files: `git -C "$PR_WORKTREE" diff --name-only <range>`.
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
   `emil-design-eng` principles) in the same in-session apply flow Step 3
   documents, landing in review-pr's commit. The visual-polish commit from
   step 3 above is separate and already made by then -- a `/pr-sentinel` run
   can land two commits from this pass alone.

### Step 2 — ponytail pass

1. Invoke the `ponytail-review` skill against that PR diff. It reports
   over-engineering findings only — it does not apply fixes itself.
2. Show the findings to the user and ask for approval before touching
   anything (findings can be wrong, and this is a shared PR, not a scratch
   diff — get a yes before editing).
3. Once approved, apply each fix directly with Edit on the files under
   `$PR_WORKTREE`, then `git -C "$PR_WORKTREE" add` exactly what you changed,
   and create one commit for this pass (`git -C "$PR_WORKTREE" commit`). Don't
   touch anything outside what the findings named, and don't revert or discard
   work that isn't itself a finding.
4. If ponytail-review found nothing, say so and move straight to Step 3 —
   no empty commit.

### Step 3 — PR-toolkit pass

1. Invoke the `review-pr` skill, scoped to the same PR
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
   Otherwise ask **one** `AskUserQuestion`, header `"Fixes"`, question
   "Apply these fixes?":
   - `"Apply fixes in-session"`
   - `"Don't fix — report only"`
4. **Apply:** apply each fix directly with Edit on the files under
   `$PR_WORKTREE`, then `git -C "$PR_WORKTREE" add` exactly the files you
   changed, and create ONE new commit (`git -C "$PR_WORKTREE" commit`) for this
   pass's fixes -- review-pr's and design-council's fixes both land in this
   same commit. Never amend an existing commit or rewrite history that might
   already be shared.
5. **Report-only:** skip straight to Step 4 — no edits, no commit.

### Step 4 — report

One summary covering all passes: what design-council applied as visual
polish (and its commit, if separate) plus what it proposed for motion, what
ponytail cut (or "already lean"), what review-pr found and fixed, and
anything left unfixed with why. State plainly that this pass may have
produced more than one commit — design-council's own polish commit, then the
shared ponytail/review-pr/motion commit. Include the diff range used (full
or incremental), the PR number, and the `$PR_WORKTREE` path the commits landed
in, so the user knows what was covered and where. Don't push — that stays a
separate, explicit action the user takes next, from `$PR_WORKTREE` on the
`<head>` branch.
