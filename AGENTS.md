## Tool Usage

Prefer `intellij-index` MCP tools for all code navigation and refactoring.

Exclude build artifacts in every search: skip `build/`, `bin/`, `out/`, `*.class`, `*.jar`.

Before reading any large file, use `ide_find_symbol` or `ide_search_text` to locate
the target method/class and its line range. Then read only that range — never read a
whole file when you only need one method.

Batch all information-gathering tool calls into a single parallel round. Do not
interleave reads with partial conclusions: collect all needed context first, then reason.

## Reasoning Discipline

When performing a spec-vs-code gap analysis, output a structured list in the form
`(item | status: implemented/gap/partial | one-line evidence)`. Do not re-narrate
the spec in prose.

Before issuing any tool call, state in one sentence: what you expect to find and why
you need it. If you cannot state this, reconsider whether the call is necessary.

## Commit Discipline

Make atomic (minimal) commits: each commit contains exactly one logical change and
can be understood, reviewed, and reverted on its own.

- One purpose per commit. Never mix a bug fix, a refactor, a feature, formatting,
  or dependency changes in the same commit. If the diff needs "and" to describe it,
  split it.
- Keep every commit buildable: the project must compile and existing tests must pass
  at each commit, not just at the end of the series.
- Separate behavior-preserving changes from behavior changes. Do refactors
  (renames, moves, extractions) in their own commits, before the commit that
  changes behavior.
- Keep tests with the change they verify, in the same commit. Do not defer tests
  to a later "add tests" commit.
- Do not include unrelated edi
- 
- ts (drive-by formatting, import reordering, incidental
  cleanup). If you notice something unrelated, note it and handle it in a separate
  commit.
- Stage explicitly: use `git add <path>` or `git add -p`. Never use `git add -A`
  or `git add .` without first reviewing `git diff --staged`.
- Commit right after each logical step is complete and verified. Do not accumulate
  multiple steps into one large commit at the end.
- Before each commit, state in one sentence what single change it contains. If you
  cannot, the commit is too large — split it.
- Write the subject line in imperative mood, ≤ 72 characters, describing what the
  commit does (e.g. `Extract token validation into TokenValidator`). Use the body
  only to explain why, not what.
- Do not amend, squash, rebase, or force-push existing commits unless explicitly
  asked.