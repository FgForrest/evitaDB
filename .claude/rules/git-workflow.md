# Git Workflow

GitHub repository: https://github.com/FgForrest/evitaDB
Main branch: `dev`
Release branches: `release_YYYY-M`

## Branch Naming

Format: `{issue-id}-{kebab-case-description}` (e.g., `1075-fix-session-killer-race-condition`)

## Commit Messages

```
<type>: <description>

[optional body explaining the change in detail]

Ref: #<issue-id>
```

Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`

Do not write (co)author name or date in the commit message!

## Pull Requests

Do not write (co)author name or date in the PR request.

- **Target branch**: resolve using this command:
  ```shell
  gh pr view --json baseRefName --jq '.baseRefName' 2>/dev/null \
    || git config branch.$(git branch --show-current).merge 2>/dev/null | sed 's|refs/heads/||' \
    || gh repo view --json defaultBranchRef --jq '.defaultBranchRef.name'
  ```
- **Copilot review**: `gh pr create --reviewer copilot` does not work. Create the PR first, then:
  ```shell
  gh api --method POST /repos/FgForrest/evitaDB/pulls/<PR_NUMBER>/requested_reviewers \
    -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
  ```
- Link issues in PR description (e.g., "Closes #1075")

## Review Comments

Fetch all unresolved review comments with `gh` CLI and address them one by one (ignore already resolved).

1. Examine whether each comment is valid (operate as self-confident experienced developer, don't blindly address all)
2. For each addressed comment: create a commit and reply explaining how you addressed it
3. For each declined comment: reply explaining why you decided not to address it

## Issue Tracking

**Labels**: `bug`, `enhancement`, `performance`, `maintenance`, `breaking change`, `documentation`

**Milestone**: pick nearest upcoming milestone (`gh api repos/FgForrest/evitaDB/milestones`)
