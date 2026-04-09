---
title: When an AI Agent Gets Its Hands on a Debugger
perex: |
  Agentic programming is fascinating, but it has one fundamental weakness — the agent can't see what's actually happening inside a running application. We built an MCP server that gives AI agents full access to the Java debugger via the JDWP protocol. In this article, we'll look at how it works, how you can try it yourself, and why it helps us in developing evitaDB.
date: '9.4.2026'
author: 'Jan Novotný'
motive: assets/images/24-mcp-jdwp-java-plugin.png
proofreading: 'done'
---

If you work with AI agents on Java projects, you probably know the feeling — the agent writes code, runs a test, the test fails, and then the familiar loop begins. In the better case, the agent attempts a blind fix based on the error output. In the worse case, it starts littering the code with `System.out.println` and debug logs, forcing a full project recompilation. Each such cycle costs time — and those debug statements then linger in the code as unsightly artifacts that the agent forgets to clean up, only for you to discover them during code review.

As developers, we'd instinctively reach for the debugger in such a situation — set a breakpoint and within minutes see what's actually going on. No recompilations, no polluting the code with temporary print statements. Until now, agents didn't have that option.

We decided to change that, and the result is [MCP JDWP Inspector](https://github.com/FgForrest/mcp-jdwp-java) — an MCP server that gives AI agents full access to the Java debugger through the standard JDWP (Java Debug Wire Protocol) interface. In other words — the agent can now set a breakpoint, suspend a thread, inspect local variables, evaluate an expression, and resume execution exactly the same way you do in IntelliJ IDEA.

## Why We Built This

When developing [evitaDB](https://evitadb.io), we regularly encounter situations that require debugging non-trivial logic — whether it's complex catalog queries, bitmap operations, or concurrent access to data structures. We catch many of these problems in fuzz tests that generate random combinations of input data and verify result consistency. When such a test fails, the root cause is often buried deep and you won't uncover it from the error output alone.

Previously, we had to manually analyze the output and then sit at the debugger ourselves. Today, an agent connected via MCP JDWP Inspector can perform the entire analysis autonomously — it finds the failure point, sets breakpoints, inspects variable state, and in the vast majority of cases identifies the root cause on its own. The time saved adds up to hours.

## What the Agent Can Do

MCP JDWP Inspector provides 40 tools organized into logical groups:

- **Breakpoints** — standard line breakpoints, conditional breakpoints (suspend only when a Java condition is met), exception breakpoints (catch them right at the throw site), and deferred breakpoints that activate automatically when the given class is loaded
- **Logpoints** — evaluate an expression at a given line without suspending the thread, ideal for non-intrusive observation
- **Inspection** — browse threads, call stacks, local variables, and object fields with automatic filtering of noise from JVM internals and frameworks
- **Expression evaluation** — compile and execute arbitrary Java code directly in the context of the suspended application with full classpath access
- **Runtime mutation** — modify values of local variables and object fields, invaluable for hypothesis testing
- **Watchers** — persistent expressions attached to breakpoints that are automatically evaluated on every hit

Everything runs locally, with zero outbound HTTP requests. Source code is compiled directly from the repository — no downloaded binaries, full auditability.

## Getting Started — Quick Setup

You'll need JDK 17+ (note: not JRE — the debugging interface requires the `jdk.jdi` module) and Maven 3.8+. There are two installation paths:

- **Plugin marketplace** — two commands in Claude Code and you're done, the JAR is compiled automatically from a local clone
- **Manual registration** — clone the repository, build with Maven, and register the MCP server in your agent's configuration

Then just launch the target Java application with the JDWP agent on port 5005 (for Maven tests, `mvn test -Dmaven.surefire.debug` is all you need) and the agent connects to it.

The complete guide including configuration examples is in the [project README](https://github.com/FgForrest/mcp-jdwp-java#readme).

## Test-Flight: Try It on Your Own Agent

So you don't have to test the plugin on your own project right away, we've prepared a `jdwp-sandbox` module in the repository with five deliberately broken Java classes. Each simulates a different type of real-world bug — from silent mutations through `hashCode()` problems and swallowed exceptions to race conditions and Java Memory Model visibility violations. Difficulty ranges from warm-up to genuinely tricky.

Just clone [our repository](https://github.com/FgForrest/mcp-jdwp-java), run the tests in the sandbox module with the JDWP agent, and tell the agent something like: *"Use JDWP to debug the VanishingPenniesTest class — find the root cause."* Descriptions of individual exercises and launch instructions are in the [README](https://github.com/FgForrest/mcp-jdwp-java#-find-the-bug--learning-exercises).

Watching the agent systematically walk through the code, set breakpoints, and gradually work its way to the root cause is an experience in itself — one that will change your perspective on what agentic programming can do.

## How We Use It in evitaDB

In evitaDB, we have an extensive suite of fuzz tests that combine random operations on the catalog — inserting entities, schema changes, concurrent queries — and verify that results are always consistent. When such a test fails, the bug is deterministic but may only manifest after hundreds of generations and takes a while to reach. At the same time, resolving it requires setting breakpoints with surgically precise conditions to capture the specific scenario, which is very difficult and time-consuming without a debugger.

MCP JDWP Inspector has dramatically sped up our analysis in these situations. The agent walks through the failing test on its own, sets breakpoints at suspicious locations, monitors data structure state, and in the vast majority of cases identifies the cause without our intervention. For more complex issues, it at least significantly narrows down the area where we need to look.

It's especially useful for concurrency bugs — race conditions and deadlocks — where human analysis from a stack trace is often like looking for a needle in a haystack. An agent with debugger access can systematically inspect the state of individual threads and reconstruct the sequence of events far more reliably.

## Wrapping Up

MCP JDWP Inspector is an open source project under the MIT license. It builds on the original work by [Nicolas Vautrin](https://github.com/nicovMusic/mcp-jdwp-inspector), which we extended with features needed for real-world use — conditional breakpoints, deferred activation, recursive breakpoint protection, logpoints, and many more. A pull request back to his repository didn't make sense, as our plugin significantly changes the original concept and adds features that would be unnecessary for his use case.

If you work on a Java project and use AI agents, we wholeheartedly recommend giving the plugin a try — at least on those five sandbox puzzles. It's the fastest way to understand what a difference it makes when the agent can "see inside" a running application.

We'd love to hear about your experiences, ideas, or any bug reports directly on [GitHub](https://github.com/FgForrest/mcp-jdwp-java/issues).
