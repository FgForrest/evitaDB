---
name: gql-breaking-changes
description: Analyze GraphQL breaking changes between the public evitaDB demo server schema and the current local branch's schema. Builds the project, boots the local server, runs graphql-inspector via `tools/diff-graphql-schemas.sh`, and writes a prose summary of breaking changes and their client-facing implications. If the user provides a path to a downstream project that generates a GraphQL client from evitaDB's schema, the summary is tailored to that project specifically (TypeScript-heavy by default). Read-only: never modifies project code.
allowed-tools: Read, Grep, Glob, AskUserQuestion, Bash(mvn *), Bash(bash *), Bash(./tools/diff-graphql-schemas.sh *), Bash(tools/diff-graphql-schemas.sh *), Bash(./evita_server/run-server.sh *), Bash(evita_server/run-server.sh *), Bash(curl *), Bash(pkill *), Bash(kill *), Bash(lsof *), Bash(cat *), Bash(wc *), Bash(ls *), Bash(test *), Bash(sleep *), Bash(jps *), Bash(pgrep *)
---

# GQL Breaking Changes Analysis

## Overview

This skill compares the GraphQL schema served by the public evitaDB demo
(`https://demo.evitadb.io/gql/evita`) against the schema served by a local
evitaDB server built from the current branch, and reports breaking changes
together with their implications for API clients.

The intended use is to evaluate, before merging or releasing, how the
current branch's API changes affect downstream consumers — in particular
TypeScript applications that auto-generate a client from the published
GraphQL schema.

## Hard Rules

- **Do NOT modify any project code.** This skill is read-only on the evitaDB
  tree. It only builds, runs, and observes.
- **Do NOT commit, push, or stage anything.** No git writes.
- **Do NOT touch** files in a downstream client project if the user points
  at one — only read them to tailor the summary.
- If a required prerequisite (docker, curl, maven, network) is missing,
  stop and report to the user. Do not improvise a workaround that alters
  the tree.

## When to Use

- The user asks for a GraphQL breaking-change analysis against the demo.
- The user asks "what would break for our clients if we released this?"
- The user mentions the `diff-graphql-schemas.sh` script and wants a
  human-readable report on top of it.

## Inputs (ask up front if not given)

Before starting the workflow, use `AskUserQuestion` to collect anything
the user did not supply explicitly:

1. **Client project path (optional)** — absolute path to a downstream
   project that generates a GraphQL client from the evitaDB schema. If
   provided, the summary in Step 5 is tailored to that project. If not,
   the summary discusses general TypeScript-client implications.
2. **Original schema URL override (optional)** — default
   `https://demo.evitadb.io/gql/evita`. Ask only if the user hinted they
   want to compare against something other than the public demo.
3. **Skip build? (optional)** — if the user says they already built the
   project in this shell session, you may skip Step 1. Otherwise always
   build, because the server jar must reflect the current branch.

Do not ask about the local URL — keep the script default
(`https://localhost:5555/gql/evita`) unless the user explicitly overrides
it. Do not ask about the output file — keep the script default
(`graphql-schema-diff.txt` at the project root).

## Workflow

Execute the steps **in order**. On any failure, stop, clean up any server
you started (Step 6), and report to the user.

### Step 1: Build the project

From the repository root:

```shell
mvn clean install -DskipTests
```

Skip JUnit tests — this skill compares schemas, it does not need the
test suite green. `-DskipTests` still compiles test sources (which some
modules rely on for packaging) but does not execute them.

This can still take several minutes. Run it in the foreground so its
exit code is observable. If the build fails, stop and report the
failing module — do not attempt fixes.

Skip this step only if the user explicitly confirmed the jar is already
fresh.

### Step 2: Start the local server in the background

The server script expects to be executed from the `evita_server/`
directory (paths inside the script are relative).

```shell
cd evita_server && bash ./run-server.sh
```

Run this with `run_in_background: true` via the `Bash` tool. Capture the
background shell id — you will need it in Step 6 to stop the server.

### Step 3: Wait until the server is ready

Poll the local GraphQL endpoint until it responds with a non-empty body.
The schema download inside the diff script uses `-k` / `--insecure`, so
the self-signed TLS certificate is expected and tolerated.

```shell
# Poll for up to ~120s. The server takes noticeable time to warm up,
# especially the first run after a clean build.
for i in $(seq 1 60); do
    if curl --silent --show-error --fail-with-body --insecure \
            --header 'Accept: application/graphql' \
            --output /dev/null \
            "https://localhost:5555/gql/evita"; then
        echo "server is up"
        break
    fi
    sleep 2
done
```

If the loop exits without a successful probe, stop the server (Step 6),
dump the tail of its stdout/stderr for context, and report. Common
causes: port 5555 occupied, missing data directory, previous server
still running. Do not attempt to kill unrelated processes blindly —
report what is bound to the port (`lsof -i :5555`) and let the user
decide.

### Step 4: Run the schema diff

From the repository root:

```shell
./tools/diff-graphql-schemas.sh
```

The script:

- downloads both schemas via `curl --insecure`,
- runs `kamilkisiela/graphql-inspector` via Docker,
- writes the full diff to `graphql-schema-diff.txt` at the project root,
- always exits 0 — a non-zero *inspector* exit code (printed on stdout)
  means breaking changes were found, which is the normal case here.

If the script itself fails (curl error, docker missing, schema download
empty) stop, clean up, and report. Do not retry blindly.

Requirements the script checks for: `curl`, `docker`. If Docker is not
running, the user will see a Docker daemon error — surface that verbatim.

### Step 5: Analyze and summarize

Read `graphql-schema-diff.txt` in full. Its format is
`graphql-inspector`'s text output: each entry is tagged as `breaking`,
`dangerous`, or `non-breaking`, followed by a description and a path.

Group findings into these categories and write a Markdown summary (printed
to the user, not written to a file unless the user asks):

1. **Breaking changes** — ordered by severity:
    - Removed types / fields / enum values
    - Type narrowing (field type becomes more restrictive)
    - Required argument added to an existing field
    - Nullable field becoming non-nullable on inputs, or vice versa on
      outputs
    - Changed directive semantics that clients rely on
2. **Dangerous changes** — additions that can break permissive client
   codegen (e.g., new enum value in an output enum, new optional input
   field with no default).
3. **Safe additions** — new types, fields, args, enum values on inputs
   only, deprecations. Mention briefly; do not enumerate exhaustively.

For **each breaking or dangerous entry**, provide:

- The exact path from the inspector output (verbatim — do not paraphrase
  GraphQL identifiers).
- What the change is.
- Concrete client-side impact.
- Suggested remediation for the client (what the client author needs to
  change, and/or which evitaDB version introduces the change — if
  detectable from commit history, otherwise omit).

#### Tailoring the summary

- **If the user supplied a client project path**: read through that
  project (Glob + Grep — no writes) to find where GraphQL operations are
  defined and which types they consume. Typical signals:
    - `*.graphql`, `*.gql`, `*.ts`, `*.tsx` files with `gql\`...\`` or
      `graphql\`...\`` template literals
    - `schema.graphql`, generated `*.generated.ts`, `__generated__/`
      directories
    - `codegen.yml` / `codegen.ts` / `graphql.config.{ts,js,yml}`
    - `apollo.config.*`, Relay compiler config, `urql` codegen
  For each breaking change, check whether the affected type, field, or
  enum value is actually referenced in the client. If it is not, say so
  explicitly ("no references found in `<project>`") — that is the most
  useful signal. If it is, point to the exact file(s) and describe the
  fix in terms of that project's codegen setup.
  In this mode, **do not** include generic TypeScript advice — only the
  project-specific impact.

- **If no client project was supplied**: give general implications for
  TypeScript clients, framed around the common codegen stacks
  (`graphql-code-generator`, Apollo, Relay, urql). Call out patterns
  that are likely to fail at build time vs. at runtime (e.g. removed
  enum value → TS exhaustiveness check breaks at compile time; nullable→non-nullable
  on output → runtime surprise if the client relied on `null`).

#### Report shape

Open with a one-line headline: "N breaking, M dangerous, K safe additions."

Then sections in this order, omitting any that are empty:

- `## Breaking changes`
- `## Dangerous changes`
- `## Safe additions (summary)`
- `## Client impact` — the tailored or general analysis described above
- `## Recommended next steps` — short, actionable bullet list

Keep the full raw diff file on disk at `graphql-schema-diff.txt` and
reference it at the end ("full machine-readable diff: ...").

### Step 6: Stop the server

Always stop the background server before returning to the user, even if
an earlier step failed.

```shell
# Preferred: kill the shell you started the server in (by background id)
# Fallback: kill the java process holding the debug agent on *:8005,
# which is unique to this script.
pkill -f 'evita-server.jar' || true
```

Confirm the port is free afterward (`lsof -i :5555` returns nothing) and
note it in the final output. If the server refuses to die, report the
PID to the user — do not escalate to `kill -9` without their say-so.

## Output

The only artifact this skill writes to disk is the diff file produced by
`diff-graphql-schemas.sh` itself (`graphql-schema-diff.txt`). The human
summary goes into the chat. Do not create additional files unless the
user asks.
