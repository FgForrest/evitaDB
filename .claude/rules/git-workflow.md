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

## Reviewing a PR

Before writing any review comments, gather the full review history so you don't repeat resolved concerns or ignore prior discussion:

```shell
# All prior review rounds (who reviewed, state, body)
gh api repos/FgForrest/evitaDB/pulls/<PR_NUMBER>/reviews

# All review comments including reply threads (in_reply_to_id links replies to parents)
gh api repos/FgForrest/evitaDB/pulls/<PR_NUMBER>/comments

# Top-level PR conversation comments
gh api repos/FgForrest/evitaDB/issues/<PR_NUMBER>/comments
```

Build a threaded view by grouping comments via `in_reply_to_id`. For each thread, read the original complaint **and** all replies/reactions. Take into account:

- Whether the author already addressed or explained a concern in a reply
- Whether a previous reviewer withdrew, softened, or refined their complaint after discussion
- Whether a concern from an earlier review round was already fixed in a subsequent commit

Do not raise issues that were already resolved or adequately answered. Focus new review comments on genuinely unaddressed problems.

## Addressing Review Comments

Fetch all unresolved review comments with `gh` CLI and address them one by one (ignore already resolved).

1. Examine whether each comment is valid (operate as self-confident experienced developer, don't blindly address all)
2. For each addressed comment: create a commit and reply explaining how you addressed it
3. For each declined comment: reply explaining why you decided not to address it

## Issue Tracking

**Labels**: `bug`, `enhancement`, `performance`, `maintenance`, `breaking change`, `documentation`

**Milestone**: pick nearest upcoming milestone (`gh api repos/FgForrest/evitaDB/milestones`)
