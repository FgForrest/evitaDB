---
title: Fit every release into Maven Central's limits; the shaded driver becomes a recipe, a BOM aligns versions
date: 2026-10-26
updated: 2026-10-26 14:05
status: accepted
kind: infrastructure
issues: [1805]
prs: []
areas: [pom.xml, evita_bom, recipes, jacoco, evita_external_api/evita_external_api_lab, .github/workflows]
supersedes: []
superseded-by: []
relates: [2026-09-08-jdk21-safe-modernization]
---

# Fit every release into Maven Central's limits; the shaded driver becomes a recipe, a BOM aligns versions

A release used to publish 109 MB and 412 files to Maven Central. It now publishes about 35 MB and 390 files.
Three changes produce the cut:

- `evita_java_driver_all_in_one` is no longer published. It lives in `recipes/` as a standalone POM that
  anyone can build against the artifacts already on Central.
- The internal `jacoco` module is no longer published.
- The evitaLab sources jar no longer carries a second copy of the GUI.

A new `evita_bom` lets consumers import one artifact and drop the versions from their evitaDB dependencies.

## Why

Maven Central now enforces monthly publishing limits per organization. On the free tier they are 80 MB, 1,000
files and 7 releases per calendar month. Exceeding one starts a 30-day grace period, and after it "new releases
that would keep that metric over the limit are not accepted". Ours ended on 2026-11-01. A single release of the
old shape was over the monthly size cap on its own, so slowing the cadence could not have helped.

How Central counts was established empirically, not taken from its documentation. The Usage Center reported
1,236 files and 326.74 MB for October 2026. The three October versions (2026.2.19, 2026.2.20, 2026.2.21)
add up to exactly that, file by file, on `repo1.maven.org`. Three things follow from the match:

- MB is decimal (10⁶ bytes).
- Every published file costs four counted files: the file, its `.asc`, and its `.md5` and `.sha1`. Central
  requires all three companions, so four per file is the floor.
- SNAPSHOT deploys are not counted.

On that model, each published jar module costs 16 files per release (pom, jar, sources, javadoc) and each
pom-only module costs 4.

### Previous state

One release (2026.2.21) was 108.99 MB and 412 files. Of that:

- `evita_java_driver_all_in_one` was 70.60 MB: a 49.23 MB shaded jar and a 21.07 MB sources jar,
  produced by `createSourcesJar` with the shaded sources of every dependency.
- The `evita_external_api_lab` sources jar duplicated 3.53 MB of `META-INF/lab/gui/dist`.
- `jacoco` (the coverage aggregator, built under `-P full`) cost 12 files. The test modules had already
  stopped publishing at 2026.1.20, but `jacoco` never got the deploy-skip pair.
- Every javadoc jar except the all-in-one driver's was an empty 317-byte placeholder (javadoc runs with
  `failOnError=false`). Central explicitly allows placeholders.

The release ran `-P full` for one reason: to get the all-in-one driver into the reactor and so onto Central.

Release cadence over the 12 months before the change: a median of 5 a month, with peaks of 16 (2026-01) and
12 (2026-09, six of them on 2026-09-09).

## Options considered

### Option A: cut the shaded driver to a recipe, stop publishing `jacoco`, de-duplicate the lab sources (chosen)

- **Pros:** 34.65 MB and 384 files per release (388 with the BOM), so 2 releases a month fit the free tier
  on both size and file count. No consumer of the remaining artifacts notices. The shaded driver stays
  available for any version: the recipe rebuilds the 2026.2.21 jar entry for entry.
- **Cons:** consumers of `evita_java_driver_all_in_one` have to build it, or switch to `evita_java_driver`.
  That is a breaking change, and #1805 carries the upgrade note.

### Option B: keep the all-in-one driver on Central with placeholder sources and javadoc (declined)

- **Pros:** consumers keep resolvable coordinates.
- **Rejected because:** the release would still be 83.89 MB, over the monthly cap for a single release.

### Option C: slim the all-in-one driver (drop the Netty native libraries, `minimizeJar`) (declined)

- **Pros:** keeps the coordinates.
- **Rejected because:** Armeria alone is 12.7 MB of the jar and the native libraries 6 MB. A slimmed jar of
  ~43 MB plus the rest of the release allows at most one release a month. `minimizeJar` was already commented
  out because reflection and `ServiceLoader` use in gRPC and Netty break it.
- **Revisit if:** the size limit rises well above 120 MB a month.

### Option D: limit the release cadence (declined)

- **Options:** gate the Central deploy to 2 a month, or ship patches on a fixed schedule.
- **Rejected because:** patch releases ship when a production bug needs fixing. That is a product requirement,
  not a tunable. The volume above two a month is being negotiated with Sonatype (an exemption, or Publisher
  Pro) instead of being cut.

### Option E: consolidate modules to cut the file count (deferred)

- **What it would mean:** for example, folding `evita_store_entity`, `evita_store_key_value` and
  `evita_traffic_engine` into `evita_store_server` would save 48 files per release.
- **Rejected because:** it renames artifacts consumers may depend on directly, it is a large JPMS and SPI
  refactor, and it would buy at most one extra release a month. Three releases a month also need
  ≤ 26.67 MB per release, which this does not reach.
- **Revisit if:** the Sonatype negotiation fails, and the file count, not size, becomes the binding limit.

### Option F: host the shaded driver in another Maven repository (not now)

- **Candidates:** our own static repository, GitHub Packages, or JitPack.
- **Rejected because:** GitHub Packages requires authentication even for public packages, and JitPack would
  have to build the whole JDK 21 + toolchains reactor on demand. Our own repository is infrastructure we would
  have to keep alive. The recipe covers the need without any of it.
- **Revisit if:** consumers ask for resolvable coordinates in numbers.

## Decision

**Chosen: Option A,** because it is the only option that brings a single release under the monthly size cap
without taking anything from the consumers of the remaining artifacts. Every remaining artifact can be
reached from a documented entry point: the driver, embedded `evita_db`, the web APIs, and
`evita_test_support`, which depends on `evita_server` and through it on every API module, `evita_external_api_lab`
included. So nothing else could leave without restructuring those dependencies.

The BOM is added in the same change because it is cheap (4 files and ~20 KB per release) and because it
makes the remaining many-module layout easier to consume.

## Key technical details

- **Deploy skip takes two properties.** `maven.deploy.skip` **and** `skipNexusStagingDeployMojo` are both
  needed, because the root POM runs `nexus-staging-maven-plugin` as a build extension. `jacoco/pom.xml` now
  carries both, like the test modules.
- **The lab sources exclusion is plugin-level configuration.** The release invokes `source:jar` as a
  command-line goal, which ignores execution-level configuration. See
  `evita_external_api/evita_external_api_lab/pom.xml`.
- **The recipe (`recipes/evita_java_driver_all_in_one/pom.xml`) is standalone.**
  - It has no `evita_root` parent and needs a `-Drevision`, with no default, because a default goes stale.
    It builds on JDK 17+ without toolchains, against any released `evita_java_driver`.
  - It **imports `evita_root` as a BOM** to reproduce the library versions evitaDB was built with. Without
    that import, Maven's nearest-wins mediation picked Guava `-android` and older Jackson and Netty patch
    releases. The pins cannot leak, because everything is shaded.
  - Flatten's `fatjar` mode installs a POM with a concrete version and no dependencies.
    `resolveCiFriendliesOnly` with custom `pomElements` throws in flatten 1.8.0.
  - The NOTICE transformer pins `UTF-8`, because JDK 17 still defaults to the platform charset.
- **CI builds the shaded jar from the recipe.** `ci-dev.yml` and `pr-review.yml` run a targeted
  `install -pl :evita_java_driver -am`, then the recipe at the reactor version, and upload its jar under the
  old artifact name for the `driver-jdk17` job. So the recipe cannot rot unnoticed. A change under
  `recipes/**` triggers CI but never a release.
- **The release no longer runs `-P full`.** Every published artifact is in the default reactor.
- **`evita_bom` inherits `evita_root` but is published flattened** (`flattenMode=bom`, with `dependencies`
  and `build` removed). `import` scope takes the BOM's *effective* dependency management, parent included, so a
  non-flattened BOM would pin Jackson, gRPC, Netty, Armeria and Kryo in every consumer. The inheritance keeps
  `versions:set` and the Central metadata working.
- **The BOM lists exactly the deployed `io.evitadb` artifacts** (24, `evita_db` as `<type>pom</type>`). An
  entry for an undeployed module resolves in our own builds and fails for consumers. The comment in
  `evita_bom/pom.xml` says so.

## Verification

- **The size model reconciles to the hundredth with the Usage Center** (1,236 files, 326.74 MB for October
  2026). After the change, the model gives 34.65 MB and 384 files per release, plus 4 files and ~20 KB for
  the BOM.
- **Recipe, released version:** built on JDK 17 with `-Drevision=2026.2.21`, the jar is entry for entry
  identical to the `evita_java_driver_all_in_one-2026.2.21.jar` on Central: 21,108 entries, all byte-equal
  except `MANIFEST.MF` (jar plugin 3.5.1 vs 3.5.0) and the embedded `pom.xml`. The installed POM holds
  `<version>2026.2.21</version>` and no dependencies.
- **Recipe, CI path:** the reactor build, the targeted driver install, then the recipe at `2026.3-SNAPSHOT`.
  `tools/verify-driver-on-jdk.sh` against the locally built server printed `SMOKE OK on Java 17`.
- **BOM:** the installed POM has no parent, 24 `io.evitadb` management entries, nothing else managed, and all
  six metadata elements Central requires. A throwaway consumer importing it got 24 managed entries, 0
  third-party, and resolved a versionless `evita_java_driver` at `2026.3-SNAPSHOT`.
- **Lab sources jar:** 18,634 bytes (9 Java files, 1 service file, no GUI entries). The main jar keeps all 45
  GUI entries.
- **Reactor:** `mvn -T 1C -Dmaven.test.skip=true install -pl '!:evita_long_running_tests'` succeeded, 30
  modules.

## Consequences & open follow-ups

- **The budget is a design constraint from now on.** Every new published jar module costs 16 files per
  release. A shaded or uber-jar artifact does not belong on Central: publish a recipe instead.
- **The Sonatype outcome is open.** An exemption or Publisher Pro decides whether more than two releases a
  month can be published. Sonatype also has to say how "Relative Popularity" (98.31; the free limit is shown
  as the 91st percentile) relates to the plan, since its documentation calls it informational.
- **The 2026.2 line still publishes the old shape** until this change is backported to `master`.
- **Plain `evita_java_driver` consumers resolve Guava `-android` and older Jackson and Netty patch releases**
  than the versions evitaDB tests with. Maven's nearest-wins mediation does that, and the recipe's import of
  `evita_root` avoids it. This predates the change. The BOM deliberately does not pin third-party versions.
- **Javadoc jars stay empty placeholders on purpose.** Generating real javadoc would add megabytes to every
  release.

## Related work

- `2026-09-08-jdk21-safe-modernization`: the `driver-jdk17` smoke test it introduced now runs the shaded
  jar built from the recipe instead of from the reactor module.

## Timeline

- **2026-10-26**: Usage Center limit warning; audit; decision; Sonatype contacted; #1805 implemented.
