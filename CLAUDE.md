## Forge Development Standards

# Forge Development Standards

Follow clean-code best practices for your project's language and framework.

## Architecture

- Follow a layered architecture: routes/controllers -> services -> repositories/data-access -> providers
- Services receive dependencies through constructor injection — no hidden coupling
- External integrations use the strategy/factory pattern — depend on interfaces, not vendor SDKs
- Repositories contain zero business logic — only CRUD and queries
- Route handlers are thin: parse request, call service, format response

## File Discipline

- Keep files focused on a single responsibility
- Split files exceeding 500 lines — extract helpers, sub-services, or utility functions
- Move shared types/interfaces to dedicated type files

## Comments

- Explain **why**, not **what** — the code already says what it does
- Add comments for non-obvious business rules, workarounds, and trade-off decisions
- No commented-out code — use git history
- No TODOs without a ticket reference

## Error Handling

- Services throw descriptive errors; routes catch and return appropriate status codes
- Never silently swallow errors without documenting why
- Error messages should include what failed and how to fix it

## Unit Testing

- Every new or modified source file MUST have a corresponding unit test file
- Tests must cover: happy path, error/edge cases, and boundary conditions
- Mock external dependencies (databases, APIs, file systems, AI providers)
- Never commit code without running tests and confirming they pass
- Aim for meaningful coverage — test behavior, not implementation details

## Branching & PR Workflow

Branching, committing, pushing, and PR timing are **user-driven**. Never pick a branch name, commit, push, or open a PR on your own — always defer the decision to the user.

- **Never push directly to the default branch.** Always work on a feature branch.
- **At the start of each work order — ask the user which branch to use:**
  - First inspect the repo for existing naming conventions: `git branch -r | head -20`.
  - If you are NOT already on a feature branch, ask: "Which branching convention would you like to use? I can see the repo uses `<observed patterns>`. Options: reuse one of those patterns, use `wo/<short-id>`, or give me a name of your choice."
  - If you ARE already on a feature branch from a previous work order, ask: "Continue on the existing branch `<name>` or create a new one?"
  - Only create or switch branches after the user confirms. When creating a new branch, base it off the repo's `default_branch` (from `set_project` / `list_linked_repos` — do NOT assume `main`):
    ```
    git fetch origin
    git checkout -b <user-chosen-branch> origin/<default_branch>
    ```
- **At the end of each work order — ask before committing:**
  - Summarize what changed and ask: "Ready to commit the changes for `<WO-id>`?" Wait for confirmation.
  - Commit only after the user agrees. One commit per work order — never batch multiple WOs into a single commit.
- **After the commit — ask before pushing / opening a PR:**
  - Ask: "Push this branch and open a PR now, or keep working on more work orders on the same branch first?"
  - Only push and call `create_pull_request` when the user opts in. The tool returns a provider-specific `cli_command` (GitHub, GitLab, Azure DevOps, or Bitbucket) — run it, then report `pr_url` and `pr_number` back via `update_work_order`.
  - Always pass `branch_name`, `repo_url`, and `repo_name` to `create_pull_request`.
- **Never start a new work order with uncommitted changes from the previous one** — resolve or stash first, and confirm with the user.

## Forge Process Flow

- Read the work order + RTM traceability context before writing any code
- Cross-reference RTM rows to identify which PRD sections and architecture components are relevant
- Validate every acceptance criterion against your changes before committing
- Write/update unit tests for all changed files
- Run the full test suite and fix any failures
- One commit per work order — never batch multiple WOs into a single commit. (Whether to commit, push, and open a PR is the user's call — see "Branching & PR Workflow" above.)
- Never start a new work order with uncommitted changes from the previous one
