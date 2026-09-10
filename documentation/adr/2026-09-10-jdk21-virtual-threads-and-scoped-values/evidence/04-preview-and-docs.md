# Part 04 - `--enable-preview` surfaces and documentation impact of adopting `ScopedValue` on JDK 21

Analysis only. Nothing was built or run; every file:line below was read from the checkout on
2026-09-08, and every JDK statement was verified with `javap` against the installed runtime
(OpenJDK 21.0.12+8-1-24.04-Ubuntu) unless it is explicitly labelled *from knowledge*.

**Environment caveat.** `/usr/lib/jvm/java-21-openjdk-amd64/lib/src.zip` is a dangling symlink to
`../../openjdk-21/src.zip`; only `openjdk-21-jdk-headless` is installed, `openjdk-21-source` is not,
and `unzip` is absent. The JDK 21 API and the javac/JVM mechanics were therefore verified from the
compiled runtime image (`javap -p`, `javap -v`, `javap -c` on `java.base`, `jdk.compiler` and
`jdk.jshell`), which is authoritative for signatures, annotations, bytecode and diagnostic strings.
HotSpot (C++) and the `java` launcher (`libjli`, C) are never in `src.zip`, so those statements are
from knowledge either way.

## Executive summary

1. `java.lang.ScopedValue` in JDK 21 is annotated `@PreviewFeature(feature=SCOPED_VALUES)` at class
   level; using it fails at javac time without `--enable-preview` (`{0} is a preview API and is disabled
   by default`). It has **no runtime self-check**: neither `ScopedValue` nor `ScopedValue$Carrier`
   references `jdk.internal.misc.PreviewFeatures`. The only runtime gate is the class-file minor
   version of the *caller*.
2. javac marks class files **per source file**: `ClassWriter.writeClassFile` writes minor version
   `65535` iff `preview.isEnabled() && preview.usesPreview(c.sourcefile)`; every other file compiled in
   the same invocation keeps minor `0`. So `--enable-preview` can be turned on for the whole reactor
   and only the handful of files that touch `ScopedValue` become preview-marked.
3. A marked class file loads only on **exactly JDK 21 with `--enable-preview`**; JDK 22+ refuses it
   outright (`UnsupportedClassVersionError`). Any *compiler* that reads a marked class file also needs
   the flag (`class file for {0} uses preview features of Java SE {1}`), which reaches downstream
   reactor modules, JShell in the documentation tests, javadoc, and users' own javac.
4. `--enable-preview` requires `--release`/`-source` equal to the compiler's own feature release
   (`invalid source release {0} with --enable-preview`). The root pom's `<release>21</release>` is
   therefore satisfied only by a JDK 21 javac: the toolchain, `mvn -version`, and every `setup-java`
   step in CI become an exact pin, not a minimum.
5. The JDK 21 `ScopedValue` API is **not** the final API. `Carrier.call(Callable)`, `Carrier.get(Supplier)`
   and the statics `runWhere`/`callWhere`/`getWhere` (all present in 21) were changed or removed in
   JDK 22-23 and are absent from the JDK 25 final version (*from knowledge*). Code should be confined to
   the subset that survived: `newInstance`, `where`, `Carrier.where`, `Carrier.run`, `Carrier.get(key)`,
   `get`, `isBound`, `orElse` (non-null), `orElseThrow`.
6. Using `ScopedValue` in `evita_engine` only marks `evita_engine` classes: embedded users
   (`evita_db`, `evita_test_support`), the all-in-one server jar (Docker image, `dist.zip`), the
   benchmarks uber-jar and every server-side module are affected; the Java driver artifacts
   (`evita_java_driver`, `_observability`, `_all_in_one`) are **not**.
7. Using it in `evita_api` (`TracingContext.java` holds the `ThreadLocal<Label[]> CLIENT_LABELS`
   candidate) additionally marks the driver's transitive `evita_api` jar and the shaded all-in-one
   driver, forcing **every Java driver user** onto JDK 21 + `--enable-preview`.
8. Build surfaces to change: root pom compiler `compilerArgs`, surefire `argLine` (after
   `${surefireArgLine}` so the JaCoCo agent stays first), javadoc `additionalOptions` + `release`, the
   two module surefire profiles that use `combine.self="override"` (documentation, long-running), the
   JShell builder in `JavaTestContext`, the JMH fork args (`ArtificialTestRunner.FORK_JVM_ARGS`,
   `BenchmarkForkArgs`, 25 `@Fork(jvmArgsAppend)` sites), and a new `.mvn/jvm.config` for in-process
   `exec:java`. No manifest attribute can carry the flag, so jar/shade configs stay as they are.
9. Runtime launchers to change: `docker/entrypoint.sh` (hardcode `--enable-preview` before
   `-javaagent`), `evita_server/dist/run.sh`, `evita_server/run-server.sh`,
   `run_performance_test.sh`, `src/automation/benchmark.sh` (both the parent `java` line and the
   `-jvmArgs` string), plus tracked IntelliJ run configurations and `.idea/misc.xml`. The
   `-javaagent` premain path loads only `evita_common`/observability/Byte Buddy classes, which are
   unmarked in both scenarios, so agent ordering is not a hazard; the flag position on the command line
   is irrelevant because all JVM options are parsed before the VM starts.
10. Documentation: README, CLAUDE.md, `docker/README.MD`, get-started (run-evitadb, query-our-dataset),
    operate (run, configure incl. the `java -jar` snippet), connectors/java, write-tests, developer
    test guidelines. Thread-pool sections (`configure.md` L10-34, L399-419, L511-539;
    `reference/metrics.md` L423-446, L457-471, L483-497, L499-515; `reference/jfr-events.md` L137-144)
    change only under a virtual-thread executor model, and the two reference files are generated by
    `JfrDocumentation`, not hand-edited. Czech mirrors are regenerated by Comenius, never hand-edited.

---

## Part A - the preview mechanics

### A.1 JDK 21 public API of `java.lang.ScopedValue` (verified, `javap -p`)

```
public final class java.lang.ScopedValue<T>                       // RuntimeInvisibleAnnotations:
                                                                  //   jdk.internal.javac.PreviewFeature(feature=SCOPED_VALUES)
  public static <T> java.lang.ScopedValue<T> newInstance();
  public static <T> java.lang.ScopedValue$Carrier where(java.lang.ScopedValue<T>, T);
  public static <T, R> R callWhere(java.lang.ScopedValue<T>, T, java.util.concurrent.Callable<? extends R>) throws java.lang.Exception;
  public static <T, R> R getWhere(java.lang.ScopedValue<T>, T, java.util.function.Supplier<? extends R>);
  public static <T> void runWhere(java.lang.ScopedValue<T>, T, java.lang.Runnable);
  public T get();
  public boolean isBound();
  public T orElse(T);
  public <X extends java.lang.Throwable> T orElseThrow(java.util.function.Supplier<? extends X>) throws X;
  public int hashCode();

public final class java.lang.ScopedValue$Carrier                  // also carries the PreviewFeature annotation
  public <T> java.lang.ScopedValue$Carrier where(java.lang.ScopedValue<T>, T);
  public <T> T get(java.lang.ScopedValue<T>);
  public <R> R call(java.util.concurrent.Callable<? extends R>) throws java.lang.Exception;
  public <R> R get(java.util.function.Supplier<? extends R>);
  public void run(java.lang.Runnable);
```

- The type used by `call` in JDK 21 is plain `java.util.concurrent.Callable` (checked `Exception`).
  `java.lang.ScopedValue$CallableOp` **does not exist** in JDK 21 (`javap`: "class not found").
- The annotation is class-level only (one `PreviewFeature(` occurrence in the class attributes), with
  `reflective()` left at its default `false`, i.e. compile-time gated.
- The class file of `ScopedValue` itself is `major 65, minor 0`: JDK classes are not preview-marked;
  only *callers* are.

### A.2 `jdk.internal.javac.PreviewFeature` (verified, `javap -p`)

```
public interface jdk.internal.javac.PreviewFeature extends java.lang.annotation.Annotation {
  public abstract jdk.internal.javac.PreviewFeature$Feature feature();
  public abstract boolean reflective();
}
public final class jdk.internal.javac.PreviewFeature$Feature extends java.lang.Enum<...> {
  VIRTUAL_THREADS, FOREIGN, STRING_TEMPLATES, UNNAMED, UNNAMED_CLASSES,
  SCOPED_VALUES, STRUCTURED_CONCURRENCY, TEST
}
```

### A.3 Runtime gate (verified where stated)

`jdk.internal.misc.PreviewFeatures` (verified): `isEnabled()` returns a static read of a native
`isPreviewEnabled()`; `ensureEnabled()` throws
`UnsupportedOperationException("Preview Features not enabled, need to run with --enable-preview")`.
**Neither `ScopedValue` nor `ScopedValue$Carrier` bytecode references `PreviewFeatures`** (verified
with `javap -c`), and neither do `Thread`, `Thread$Builder`, `StringTemplate` or
`StructuredTaskScope` in this build. So there is no self-check inside the API; the gate is entirely the
class-file check performed when the JVM parses the *caller's* class file.

*From knowledge, not verifiable in this checkout* (HotSpot `classFileParser.cpp`, JVMS §4.1): a class
file whose `minor_version == 65535` "uses preview features of the release identified by
`major_version`" and is rejected unless the running JVM is that exact feature release **and**
`--enable-preview` is on. The two HotSpot messages are
`java.lang.UnsupportedClassVersionError: Preview features are not enabled for <class> (class file version 65.65535). Try running with '--enable-preview'`
(right release, flag missing) and
`<class> (class file version 65.65535) was compiled with preview features that are unsupported. This version of the Java Runtime only recognizes preview features for class file version <N>.65535`
(wrong release, no flag can help).

### A.4 javac side: what gets marked, and when javac refuses (verified, `javap` on `jdk.compiler`)

`com.sun.tools.javac.code.Preview` (JDK 21):

```
private final java.util.Set<javax.tools.JavaFileObject> sourcesWithPreviewFeatures;
public void markUsesPreview(com.sun.tools.javac.util.JCDiagnostic$DiagnosticPosition);
public boolean usesPreview(javax.tools.JavaFileObject);
public boolean isEnabled();
public boolean isPreview(com.sun.tools.javac.code.Source$Feature);
public void warnPreview(javax.tools.JavaFileObject, int);
```

`com.sun.tools.javac.jvm.ClassFile`: `public static final int PREVIEW_MINOR_VERSION = 65535;`

`com.sun.tools.javac.jvm.ClassWriter.writeClassFile` (bytecode, verified):

```
1053: getfield      preview:Lcom/sun/tools/javac/code/Preview;
1056: invokevirtual Preview.isEnabled:()Z
1059: ifeq          1089
1063: getfield      preview
1067: getfield      Symbol$ClassSymbol.sourcefile:Ljavax/tools/JavaFileObject;
1070: invokevirtual Preview.usesPreview:(Ljavax/tools/JavaFileObject;)Z
1073: ifeq          1089
1080: ldc_w         // int 65535
1083: invokevirtual ByteBuffer.appendChar:(I)V
1086: goto          1103
1089: ... getfield  Target.minorVersion:I
1100: invokevirtual ByteBuffer.appendChar:(I)V
```

Conclusion: **javac marks only the class files whose *source file* used a preview feature or API**
(`markUsesPreview` records the current `JavaFileObject`; `usesPreview` is checked per class symbol's
`sourcefile`). All classes originating from a marked source file (nested, anonymous, lambda hosts) are
marked; every other source file in the same `--enable-preview` compilation is written with the normal
minor version `0`. Compiling the whole reactor with the flag is therefore safe and does not poison
unrelated jars.

Diagnostics from the `compiler` resource bundle (verified strings):

| Key | Message | When it fires |
|---|---|---|
| `compiler.err.is.preview` | `{0} is a preview API and is disabled by default.\n(use --enable-preview to enable preview APIs)` | source uses `ScopedValue` without the flag |
| `compiler.err.preview.feature.disabled.classfile` | `class file for {0} uses preview features of Java SE {1}.\n(use --enable-preview to allow loading of class files which contain preview features)` | javac *reads* a marked class file without the flag (`ClassReader` calls `Preview.isEnabled()`, then `disabledError`) - hits every downstream compiler |
| `compiler.err.preview.not.latest` | `invalid source release {0} with --enable-preview\n(preview language features are only supported for release {1})` | `--release 21 --enable-preview` on a JDK 22+ javac |
| `compiler.err.preview.without.source.or.release` | `--enable-preview must be used with either -source or --release` | flag without `--release`/`-source` (javadoc, JShell) |
| `compiler.note.preview.filename` / `.plural` | `{0} uses preview features of Java SE {1}.` / `Some input files use preview features of Java SE {0}.` | informational note per marked file; a **note**, not a warning - no `-Werror` is configured in this repository, so it cannot break the build. `-Xlint:preview` upgrades it to per-site warnings |

### A.5 What changed after JDK 21 (*from knowledge, not verifiable in this checkout*)

| JDK | JEP | API change |
|---|---|---|
| 22 | 464 (2nd preview) | `Carrier.call` and static `callWhere` take a new `ScopedValue.CallableOp<? extends R, X extends Throwable>` and throw `X` instead of `Exception`; `Carrier.get(Supplier)` and static `getWhere` removed |
| 23 | 481 (3rd preview) | static `runWhere` / `callWhere` removed; only the fluent `where(k, v).run(...)` / `.call(...)` form remains |
| 24 | 487 (4th preview) | `orElse(null)` throws `NullPointerException` |
| 25 | 506 (final) | standardised with the JDK 23/24 shape; class files compiled on 25 are no longer preview-marked |

Consequence for evitaDB: the lock-in is **source-level as well as class-file-level**. Code written
against `Carrier.call(Callable)`, `Carrier.get(Supplier)`, `runWhere`, `callWhere` or `getWhere`
will not compile when the project moves to a later JDK. Recommended: a small project-internal wrapper
that exposes only `newInstance`, `where(...).run(Runnable)`, `Carrier.where`, `get`, `isBound`,
`orElse` (never with `null`) and `orElseThrow`; checked-exception call paths go through `run` with a
holder rather than `call`.

### A.6 Which artifacts carry preview-marked classes

Dependency graph as declared in the poms (artifactIds):

- `evita_common` <- `evita_query` <- `evita_api` <- `evita_engine` <- `evita_store_key_value`,
  `evita_store_entity`, `evita_store_server`, `evita_traffic_engine`, `evita_external_api_core` <-
  `evita_external_api_{graphql,rest,grpc,system,lab,observability}` <- `evita_server`
  (`evita_server/pom.xml` L40-113; shaded into `evita-server.jar`, L164-233).
- `evita_db` (pom bundle, `evita_db/pom.xml` L37-68): `evita_common`, `evita_query`, `evita_api`,
  `evita_engine`, `evita_store_server`, `evita_store_entity`.
- `evita_java_driver` (`evita_external_api/evita_external_api_grpc/client/pom.xml` L40-45): `evita_api`,
  `evita_external_api_grpc_shared` (which depends on `evita_api`, `evita_query`).
  `evita_java_driver_observability` (`client_observability/pom.xml` L55-64): `evita_api`,
  `evita_java_driver`. `evita_java_driver_all_in_one` (`client_all_in_one/pom.xml` L38-42, shade
  L77-206) shades the driver and its transitive closure; relocations rewrite third-party packages only,
  `io.evitadb.*` classes are copied as-is.
- `jacoco` aggregate (`jacoco/pom.xml` L35-151): runtime scope on everything.

| Where `ScopedValue` is used | Jars that contain minor-65535 classes | Downstream users forced onto JDK 21 + `--enable-preview` | Unaffected |
|---|---|---|---|
| (a) `evita_engine` only | `evita_engine` (only the source files that use it); therefore inside `evita-server.jar`, `benchmarks.jar`, the `evita_db` bundle's resolution set, `jacoco` aggregate input | embedded users (`evita_db`, `evita_test_support` test users), Docker image users (flag supplied by the image), `dist.zip` users (flag supplied by `run.sh`), anyone typing `java -jar evita-server.jar` | Java driver users (`evita_java_driver*` do not depend on `evita_engine`), C# / GraphQL / REST / gRPC clients |
| (b) `evita_api` (e.g. `TracingContext.java`, whose `ThreadLocal<Label[]> CLIENT_LABELS` is at L134) | everything in (a) plus `evita_api`; therefore `evita_java_driver`'s classpath, `evita_java_driver_observability`, and the shaded `evita_java_driver_all_in_one` (marked `evita_api` classes inside) | everything in (a) plus **every Java driver user**, including frameworks that repackage the driver (Spring Boot fat jars etc.), and their javac whenever it reads `TracingContext` or another class from the same source file | non-JVM clients |

Note on (b): `TracingContext` is an interface the driver loads on every traced call, so the failure
for a driver user without the flag is immediate (`UnsupportedClassVersionError` at first use), not a
corner case.

---

## Part B - every build, runtime, packaging and IDE surface

Legend: "no change" rows are listed so the enumeration is complete and the reason is recorded.

### B.1 Root `pom.xml`

| Surface | File:line | Current | Required change | Notes |
|---|---|---|---|---|
| maven-compiler-plugin | `pom.xml:700-702` | `<compilerArgs><arg>-parameters</arg></compilerArgs>` | add `<arg>--enable-preview</arg>` after `-parameters` | Inherited by every module (child `compilerArgs` merge by default; the perf module's compiler config at `evita_test/evita_performance_tests/pom.xml:159-181` only replaces `annotationProcessorPaths`). `<release>${java.version}</release>` (L697, `java.version`=21 at L124) must stay 21 and the toolchain must *be* 21 (A.4, `preview.not.latest`). `<fork>true</fork>` + `<jdkToolchain>` (L694-696) already fork javac from the toolchain. Optional: `<arg>-Xlint:preview</arg>`; never `-Werror`. |
| maven-surefire-plugin `argLine` | `pom.xml:759-765` | `<argLine>${surefireArgLine} -Xmx${surefire.maxHeapSize} -Duser.country=US -Duser.language=en ... --add-opens java.base/java.util=ALL-UNNAMED</argLine>` | `<argLine>${surefireArgLine} --enable-preview -Xmx${surefire.maxHeapSize} -Duser.country=US -Duser.language=en ...` (insert directly after `${surefireArgLine}`) | `${surefireArgLine}` is written by jacoco `prepare-agent` (`pom.xml:856-864`, `<propertyName>surefireArgLine</propertyName>`) and expands to `-javaagent:...jacocoagent.jar=...`; keep it first and untouched. JaCoCo 0.8.15 copes with minor 65535: the cached `org.jacoco.core-0.8.15.jar` contains `InstrSupport.classReaderFor(byte[])` with a `65535` constant (verified with `javap`), which masks the preview minor before ASM parses. Note: does **not** reach the documentation and long-running modules, see B.2. Pre-existing: `--add-opens java.base/java.lang.invoke` is listed twice (L761-762). |
| maven-failsafe-plugin | - | not declared in any tracked pom (a ripgrep for `maven-failsafe-plugin` over every tracked pom is empty) | no change | - |
| maven-javadoc-plugin | `pom.xml:768-776` | `<configuration><verbose>false</verbose><quiet>true</quiet><doclint>none</doclint><failOnError>false</failOnError></configuration>` | add `<release>${java.version}</release>` and `<additionalOptions><additionalOption>--enable-preview</additionalOption></additionalOptions>` | javadoc runs javac's front end: it fails on marked sources (`is.preview`) and on reading marked dependency class files (`preview.feature.disabled.classfile`); `--enable-preview` needs `--release`, hence `<release>`. With `failOnError=false` the failure is swallowed and **no `-javadoc.jar` is attached**, so `ci-release.yml:177` and `ci-dev.yml:127` (`javadoc:jar ... deploy`) would publish without javadoc. Plugin-level config is inherited by `client_all_in_one/pom.xml:60-75`, whose `includeDependencySources` (L69) pulls `evita_api` sources - relevant in scenario (b). |
| maven-jar-plugin | `pom.xml:646-660` (pluginManagement), `evita_server/pom.xml:148-162` | manifest `mainClass`, `Premain-Class`, `Can-Redefine-Classes`, `Implementation-Build-Commit` | no change | No manifest attribute enables preview (`Add-Opens`, `Add-Exports`, `Launcher-Agent-Class`, `Enable-Native-Access` exist; nothing for preview - *from knowledge*). The in-process modular-JAR step (README L184-196) parses only `module-info.class`, which is never marked. |
| maven-shade-plugin | `pom.xml:634-637`; `evita_server/pom.xml:164-233`; `client_all_in_one/pom.xml:77-206`; `evita_test/evita_performance_tests/pom.xml:183-227` | copies/relocates classes | no change | Shaded jars simply carry the marked classes. Relocation (client_all_in_one) rewrites through ASM 9.x, which validates only the major version (*from knowledge*; confirm after a build with `javap -v` showing `minor version: 65535`). |
| maven-assembly-plugin | - | not used | no change | - |
| toolchains profile | `pom.xml:453-482` | `<jdk>[1.3,21)</jdk>` activation; `<toolchains><jdk><version>${java.version}</version><vendor>openjdk</vendor>` (L472-477) | no change to XML; update the comment at L431-451 | The `21` in `~/.m2/toolchains.xml` (README L209-213) becomes an exact pin: a 22+ toolchain fails at compile with `preview.not.latest`. |
| jacoco `prepare-agent` | `pom.xml:851-866` | `<propertyName>surefireArgLine</propertyName>`, `<append>true</append>` | no change | see surefire row |
| `.mvn/jvm.config` (new) | `.mvn/` does not exist | - | create `.mvn/jvm.config` containing `--enable-preview` | Gives Maven's own JVM the flag for in-process class loading: `exec:java` (B.2) and any plugin that loads project classes in-process. `mvn` reads it from the reactor root; IntelliJ's Maven runner honours it. Harmless to plugins that never see a marked class. Alternative: `MAVEN_OPTS="--enable-preview"` in the one script that needs it. |

### B.2 Module poms and test harness code

| Surface | File:line | Current | Required change | Notes |
|---|---|---|---|---|
| Performance tests - compiler | `evita_test/evita_performance_tests/pom.xml:159-181` | `annotationProcessorPaths` (lombok + `jmh-generator-annprocess`) | no change | `compilerArgs` inherited from root; annotation processors run inside the same `--enable-preview` javac. |
| Performance tests - uber-jar | `evita_test/evita_performance_tests/pom.xml:183-227` | shade `mainClass` `io.evitadb.performance.ArtificialTestRunner` (L194), `Add-Opens` manifest entry (L206) | no config change; extend the comment at L197-205 to say `--enable-preview` cannot ride in the manifest either | The main class is the project's own runner, not `org.openjdk.jmh.Main`. |
| JMH fork args (central) | `evita_test/evita_performance_tests/src/main/java/io/evitadb/performance/ArtificialTestRunner.java:63-68` | `private static final String[] FORK_JVM_ARGS = { "--add-opens", "java.base/java.lang=ALL-UNNAMED", ... };` | `private static final String[] FORK_JVM_ARGS = { "--enable-preview", "--add-opens", "java.base/java.lang=ALL-UNNAMED", ... };` | `.jvmArgsAppend(FORK_JVM_ARGS)` (L78, L94) is applied to both the curated suite and the `CommandLineOptions` path, so every launch through `benchmarks.jar` gets it even when `-jvmArgs` is given. JMH resolution order (*from knowledge*, JMH 1.37): explicit `-jvmArgs` or `@Fork(jvmArgs)` replaces the parent's `RuntimeMXBean.getInputArguments()`; `jvmArgsAppend` is always appended. |
| JMH fork args (per class) | `evita_test/evita_performance_tests/src/main/java/io/evitadb/performance/setup/BenchmarkForkArgs.java:57-76` | constants `ADD_OPENS`, `OPEN_LANG`, `OPEN_LANG_INVOKE`, `OPEN_MATH`, `OPEN_UTIL` | add `String ENABLE_PREVIEW = "--enable-preview";` and add it to every `@Fork(jvmArgsAppend = {...})` | 25 sites: `rg -n 'jvmArgsAppend = \{' evita_test/evita_performance_tests/src` (WalReplayBenchmark:70, SubstringCacheRepeatBenchmark:74, SubstringEagerFoldBenchmark:78, SubstringQueryBenchmark:79, SignalThroughputBenchmark:46, CommitThroughputBenchmark:73, ArtificialEntitiesLatency/ThroughputBenchmark:48/46, SchemaCapabilityUsageBenchmark:81, SortAttributeIngestBenchmark:97, OffsetIndexCompactionBenchmark:86, EqualizedHistogramCruncherBenchmark:142, the eight externalApi grpc/rest/graphql/javaDriver benchmarks, the four production-catalog benchmarks under two customer-named packages). Needed for runs that bypass `ArtificialTestRunner` (IDE, JMH plugin). |
| Long-running tests surefire | `evita_test/evita_long_running_tests/pom.xml:272` | `<argLine>${longRunningArgLine} --add-opens java.base/java.lang.invoke=ALL-UNNAMED ...</argLine>` | `<argLine>${longRunningArgLine} --enable-preview --add-opens java.base/java.lang.invoke=ALL-UNNAMED ...</argLine>` | The enclosing `<configuration combine.self="override">` (L260) discards the inherited root configuration, so the root `argLine` change never reaches this module. `${longRunningArgLine}` is defined at L49 (`-Xmx5g`). |
| Documentation tests surefire | `evita_test/evita_documentation_tests/pom.xml:238-243` | `<configuration combine.self="override"><skipTests>false</skipTests></configuration>` | add `<argLine>--enable-preview -Duser.country=US -Duser.language=en -Dfile.encoding=UTF-8 --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED</argLine>` inside that configuration, **or** drop `combine.self="override"` and let the child's `skipTests=false` merge over the parent | Same `override` semantics as the long-running module: the fork most likely runs today with no inherited `argLine` at all. Verify with `mvn help:effective-pom -P documentation -pl evita_test/evita_documentation_tests` once the benchmark window is over; if the root argLine *is* inherited, this row collapses to "no change". |
| JShell (documentation code snippets) | `evita_test/evita_documentation_tests/src/test/java/io/evitadb/documentation/java/JavaTestContext.java:163-167` | `JShell.builder().executionEngine(new LocalExecutionControlProvider(), Collections.emptyMap()).out(System.out).err(System.err).build()` | add `.compilerOptions("--enable-preview")` (and `"-source", "21"` if javac then reports `--enable-preview must be used with either -source or --release`) | JShell compiles snippets with an embedded javac that reads the test classpath (L169-170 copies `java.class.path`); when it resolves `Evita`, `EvitaSessionContract` etc. it reads their class files, and a marked one fails with `preview.feature.disabled.classfile`. Execution is local (same surefire fork), so `remoteVMOptions` is irrelevant. `JShell$Builder` exposes `compilerOptions(String...)` and `remoteVMOptions(String...)` (verified). |
| `exec:java` (javadoc summariser) | `evita_test/evita_documentation_tests/pom.xml:250-267` (profile `generate-javadoc`, `mainClass io.evitadb.documentation.javadoc.JavaDocSummarizer`, `classpathScope test`); invoked by `tools/generate-query-constraints-javadoc.sh:69` | `mvn -pl evita_test/evita_documentation_tests test-compile exec:java -Pgenerate-javadoc ...` | covered by `.mvn/jvm.config` (B.1); otherwise prefix the script line with `MAVEN_OPTS="--enable-preview $MAVEN_OPTS"` | `exec:java` runs inside Maven's JVM. `JavaDocSummarizer` imports `io.evitadb.api.query.descriptor.annotation.ConstraintDefinition` (evita_query, unmarked) and `io.evitadb.test.EvitaTestSupport` (evita_test_support, links to engine types lazily). Low risk in (a), real in (b); the jvm.config makes it moot. |
| Server all-in-one | `evita_server/pom.xml:148-162`, `:164-233` | jar manifest + shade | no change | See B.1 jar/shade rows. `Premain-Class` is `io.evitadb.externalApi.observability.agent.ErrorMonitoringAgent`. |
| Driver all-in-one | `evita_external_api/evita_external_api_grpc/client_all_in_one/pom.xml:60-75`, `:77-206` | javadoc with `includeDependencySources`; shade with relocations | no change to the pom (inherits root javadoc options) | Scenario (b) only: the shaded jar then contains marked `evita_api` classes. |
| `evita_db` bundle | `evita_db/pom.xml:37-68` | pom packaging listing six modules | no change | Embedded users inherit the requirement through it (Part C). |
| jacoco aggregate | `jacoco/pom.xml:155-183` | `report-aggregate` + excludes | no change | Reads class files via the same `InstrSupport.classReaderFor` path. |
| module-info.java files | all modules | - | no change | `ScopedValue` lives in `java.base`; module declarations cannot use preview APIs, so `module-info.class` is never marked. |

### B.3 Runtime launchers, containers, orchestration

| Surface | File:line | Current | Required change | Notes |
|---|---|---|---|---|
| Docker entrypoint | `docker/entrypoint.sh:64-67` | `exec java \` / `-javaagent:${EVITA_BIN_DIR}${EVITA_JAR_NAME} \` / `$EVITA_JAVA_OPTS \` / `-jar "${EVITA_BIN_DIR}${EVITA_JAR_NAME}" \` | insert `--enable-preview \` as the first line after `exec java \` | **Ordering.** All JVM options, `-javaagent` included, are parsed before the VM is created; premain runs after VM init, so `--enable-preview` anywhere before `-jar` covers both the agent and `EvitaServer.main`. The agent jar is the same all-in-one jar; `ErrorMonitoringAgent.premain` (`evita_external_api/evita_external_api_observability/src/main/java/io/evitadb/externalApi/observability/agent/ErrorMonitoringAgent.java:52-98`) loads `io.evitadb.exception.*` (evita_common), `ErrorOriginLogging` (observability config), Byte Buddy, and injects `ErrorMonitor` into the boot loader - none of these lives in `evita_engine` or `evita_api`, so they are unmarked in both scenarios. Without the flag the JVM would therefore start, run premain, and die at `main` with `UnsupportedClassVersionError` for the first marked class; if a marked class ever reached the agent path it would abort during agent initialisation instead. `$EVITA_JAVA_OPTS` stays after the hardcoded flag, so users cannot lose it. The `exec "$@"` branch (L76) does not get the flag - see the `JDK_JAVA_OPTIONS` row. |
| Docker image env (alternative) | `docker/Dockerfile:28-30` | `ENV EVITA_JAVA_OPTS=""` / `ENV EVITA_ARGS=""` | optional: `ENV JDK_JAVA_OPTIONS="--enable-preview"` | *From knowledge (libjli `args.c`/`main.c`, C, not in src.zip):* `JDK_JAVA_OPTIONS` is read **only by the `java` launcher** (tool launchers such as `jcmd`, `jfr`, `keytool`, `jshell`, `javac` return early from `JLI_AddArgsFromEnvVar`), its content is **prepended** to the command-line arguments, the launcher prints `NOTE: Picked up JDK_JAVA_OPTIONS: --enable-preview` on stderr at every start, and it may not contain `-jar`, a main class or terminal options like `-version`. Pros: also covers `exec "$@"` custom `java` commands and any `java` a user runs inside the container. Cons: `docker run -e JDK_JAVA_OPTIONS=...` silently replaces it and the server then dies at startup; the NOTE line lands in every log. `JAVA_TOOL_OPTIONS` would be read by every JVM including `jcmd`/`jshell` and is noisier - not recommended. **Recommendation:** hardcode in `entrypoint.sh` (deterministic, cannot be dropped by an env override) and add the `ENV` only if the custom-command path matters; specifying the flag twice is accepted (*from knowledge*). |
| Docker build script | `docker/build.sh:50-55` | `docker build ... --build-arg EVITA_JAR_NAME` | no change | Does not launch Java. |
| Base image | `docker/Dockerfile:1` | `FROM index.docker.io/azul/zulu-openjdk:21-latest` | no change, add comment `# must stay 21: evitaDB is compiled with --enable-preview against JDK 21 class files` | A bump to a 22+ tag makes the image unbootable. |
| Local compose | `evita_server/docker-compose.yml:33-34` | `EVITA_JAVA_OPTS: -agentlib:jdwp=...` | no change | Appended after the hardcoded flag. |
| Distribution launcher | `evita_server/dist/run.sh:29-31` | `exec java \` / `$EVITA_JAVA_OPTS \` / `-jar "evita-server.jar" \` | insert `--enable-preview \` after `exec java \` | Shipped in `dist.zip`/`dist.tar.gz` (`.github/workflows/ci-release.yml:184-203`). Pre-existing difference, report only: unlike the Docker entrypoint it passes no `-javaagent`. |
| Developer launcher | `evita_server/run-server.sh:31-40` | `java \` / `-agentlib:jdwp=...` / `--add-opens ...` / `-javaagent:target/evita-server.jar \` / `-jar "target/evita-server.jar" \` | insert `--enable-preview \` after `java \` (L31) | - |
| Benchmark launcher (local) | `evita_test/evita_performance_tests/run_performance_test.sh:25` | `java -jar target/benchmarks.jar` | `java --enable-preview -jar target/benchmarks.jar` | Parent JVM only loads the runner in the default forking mode; the flag matters for `-f 0` (in-process, used for JPDA debugging per `documentation/developer/test_guidelines.md:901-903`) and costs nothing otherwise. |
| Benchmark launcher (k8s image) | `evita_test/evita_performance_tests/src/automation/benchmark.sh:109-113` | `java \` / `-XshowSettings \` / `-jar benchmarks.jar "$BENCHMARK_SELECTOR" \` / `$JMH_ARGS -rf json -rff $RESULT_JSON \` / `-jvmArgs "$EXTRA_JAVA_OPTS $BENCHMARK_JAVA_OPTS -DdataFolder=/data -DevitaData=/evita-data/data"` | `java --enable-preview -XshowSettings -jar benchmarks.jar ...` and `-jvmArgs "--enable-preview $EXTRA_JAVA_OPTS $BENCHMARK_JAVA_OPTS -DdataFolder=/data -DevitaData=/evita-data/data"` | `-jvmArgs` switches off inheritance of the parent's arguments; `FORK_JVM_ARGS` (B.2) is still appended by `ArtificialTestRunner`, so the `-jvmArgs` edit is belt-and-braces. |
| Benchmark image | `evita_test/evita_performance_tests/src/automation/Dockerfile:12-13` | `apt-get install -y openjdk-21-jdk-headless` | no change, add comment that it must remain 21 | Same exact-release constraint as the server image. `build.sh:28-29` only runs `docker build`. |
| k8s job / deploy scripts | `evita_test/evita_performance_tests/src/do_k8s_automation/deploy/k8s-job.yml:21-22` (`args: ['__ARG_EXTRA_JAVA_OPTS__']`), `03-benchmark.sh:30,57,84` (`EXTRA_JAVA_OPTS`, `BENCHMARK_JAVA_OPTS` into the configmap), `00-env.sh`, `02-setup.sh`, `monitoring/*.yml` | placeholders substituted into the job | no change | Values flow into the `-jvmArgs` string above, which already carries the flag. |
| Observability compose | `docker/observability/docker-compose.yml`, `otel-desktop-viewer/docker-compose.yml`, `*.yaml` | Prometheus/Grafana/OTel/Loki/Tempo only | no change | No evitaDB JVM started there. |
| Server resources | `evita_server/src/main/resources/` (`evita-configuration.yaml`, `META-INF/logback.xml`) | no startup scripts | no change | - |
| HTTP client files | `http/external-api.http`, `http/web-sockets.http` | schema download requests | no change | - |

### B.4 CI workflows

| Surface | File:line | Current | Required change | Notes |
|---|---|---|---|---|
| Maven-driven jobs | `ci-dev.yml:89,127`; `pr-review.yml:72`; `ci-master.yml:57,65,70`; `ci-performance.yml:43`; `ci-release.yml:173,177`; `ci-dev-documentation.yml:43`; `documentation-tests.yml:41`; `long-running-tests.yml:53` | `mvn ... -P ...` | no change | Flags come from the poms and `.mvn/jvm.config`. `ci-master.yml:65,70` run `pgpverify-maven-plugin:check` and `duplicate-finder:check`; neither loads project classes. |
| JDK selection | `pr-review.yml:53-54`, `ci-dev.yml:65-66`, `ci-master.yml:48-49`, `ci-performance.yml:29-30`, `ci-release.yml:154-155`, `ci-dev-documentation.yml:32-33`, `documentation-tests.yml:35-36`, `long-running-tests.yml:47-48` | `distribution: 'temurin'` / `java-version: '21'` | no value change; add `# must stay 21 - --enable-preview pins the build to the JDK's own feature release` | A Dependabot-style bump to `'25'` would fail at compile with `preview.not.latest`. |
| Benchmark workflow | `.github/workflows/benchmark.yml:37` | `BENCHMARK_JAVA_OPTS: "-Xmx9g"` | optional: `BENCHMARK_JAVA_OPTS: "-Xmx9g --enable-preview"` | Not required once `benchmark.sh` and `FORK_JVM_ARGS` carry it; documents intent for people who copy the value. `benchmark-hook.yml`, `benchmark-clean.yml`: no Java. |
| Docker publish | `docker-latest.yml:111,203-206`; `docker-canary.yml:59-62` | unzip `dist/evita-server.jar` into `./docker`, `docker/build-push-action` with `docker/Dockerfile` | no change | Entrypoint carries the flag. |
| Release dist | `ci-release.yml:184-190` | copies `evita_server/dist/run.sh` into `dist/` | no change | `run.sh` edit (B.3) is what matters. |

### B.5 Tools and IDE

| Surface | File:line | Current | Required change | Notes |
|---|---|---|---|---|
| `tools/generate-query-constraints-javadoc.sh` | `:69` | `mvn -pl evita_test/evita_documentation_tests test-compile exec:java -Pgenerate-javadoc -q -Dexec.args="$*"` | covered by `.mvn/jvm.config`; else `MAVEN_OPTS="--enable-preview $MAVEN_OPTS" mvn ...` | See `exec:java` row in B.2. |
| other `tools/*.sh` | `tools/translate.sh` (`mvn -N comenius:run`), `audit-deprecated-since.sh`, `generate-adr-index.sh`, `diff-*-schemas.sh`, ... | no `java` launch (`rg -n '\bjava\b' --glob '*.sh'`) | no change | `diff-graphql-schemas.sh` / `diff-openapi-schemas.sh` boot the server through skills; if they invoke `java -jar` outside the repo scripts, the flag is needed there too (not in the repo). |
| IntelliJ project (tracked) | `.idea/misc.xml:83` | `<component name="ProjectRootManager" version="2" languageLevel="JDK_17" default="true" project-jdk-name="17" project-jdk-type="JavaSDK" />` | `languageLevel="JDK_21_PREVIEW" default="true" project-jdk-name="21"` | Already stale at 17 on this branch. IDEA also derives "21 (Preview)" from `--enable-preview` in `compilerArgs` on Maven re-import (*from knowledge*). `.idea/compiler.xml` and `workspace.xml` are untracked and therefore not reported. |
| IntelliJ run configuration | `.idea/runConfigurations/All_functional_tests.xml:20` | `<option name="VM_PARAMETERS" value="-ea --add-opens java.base/java.lang.invoke=ALL-UNNAMED ..." />` | `value="-ea --enable-preview --add-opens java.base/java.lang.invoke=ALL-UNNAMED ..."` | - |
| IntelliJ run configurations without VM options | `.idea/runConfigurations/Documentation_tests.xml`, `GraphQL_tests.xml`, `REST_tests.xml` | no `VM_PARAMETERS` option | add `<option name="VM_PARAMETERS" value="-ea --enable-preview --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED" />` | They currently rely on the (untracked) JUnit template; without the flag the fork cannot load marked classes. |
| IntelliJ JUnit template guidance | see Part C (`documentation/developer/test_guidelines.md:108-116`) | - | - | - |

---

## Part C - documentation impact

Process (from `.claude/rules/documentation.md`, read in full): `documentation/user/en/**` is the only
hand-written source. `documentation/user/cs/**` is generated by the Comenius Maven plugin
(`one.edee.oss:comenius-maven-plugin`, run via `tools/translate.sh` =
`mvn -N comenius:run -Dcomenius.action=translate`, needs `OPENAI_API_KEY`) and re-translates every
English file whose tracked source commit is stale, so it is run when a Czech sync is wanted, not after
every edit. Czech files are never hand-edited; the two exceptions are a mistranslation that reproduces
on two independent runs (narrow hand-fix, flagged in the commit message) and a file Comenius rejects for
blank-line drift (leave it one revision behind and say so; never edit the English source to appease the
validator). `tools/translate.sh` exits non-zero and names rejected files; a green Maven run is not proof
of a complete sync.

`documentation/user/en/operate/reference/metrics.md` and `reference/jfr-events.md` are **generated**
by `evita_test/evita_documentation_tests/src/test/java/io/evitadb/documentation/jfr/JfrDocumentation.java:75-76`
from the metric/event classes; they change by editing the event classes and re-running
`-P documentation`, not by hand. There is no `operate/monitor.md`; the monitoring page is
`operate/observe.md`, which has no thread-pool content of its own (only the generic JVM
`JvmThreadsMetrics` binder list at L224).

### C.1 Pages that state the Java version, build, run or JVM options

| Doc | Section | Current statement | Proposed statement |
|---|---|---|---|
| `README.md:158` | Prerequisites | `evitaDB requires and is tested on OpenJDK 21.` | `evitaDB requires OpenJDK 21 exactly (not newer) and must be started with the JVM flag --enable-preview: it uses the JDK 21 preview API ScopedValue, and JDK 22 or later refuses the compiled class files. Our Docker image and dist/run.sh pass the flag for you.` |
| `README.md:170-176` | How to build evitaDB | `mvn clean install` | keep; add: `The build passes --enable-preview to javac, surefire and javadoc itself; nothing is added on the command line, but mvn -version must report Java 21 and a JDK 22+ toolchain fails with "invalid source release 21 with --enable-preview".` |
| `README.md:179-181` | Maven setup | `You must have JDK 21 installed and configured in your Maven toolchains.` | `You must have exactly JDK 21 installed and configured in your Maven toolchains - the preview flag ties the build to that release.` |
| `README.md:183-196` | [!IMPORTANT] Maven itself must run on JDK 21 | one reason (modular JAR assembly) | add a second bullet: `--enable-preview requires --release to equal the compiler's own feature release, so a Maven or toolchain JDK newer than 21 fails at compile time, before packaging.` |
| `README.md:209-213` | toolchains.xml sample (`<version>21</version>`) | unchanged | add an XML comment: `<!-- must be 21 exactly; see --enable-preview note above -->` |
| `CLAUDE.md:9` | Building - Java Version | `- **Java Version**: OpenJDK 21 (requires Maven toolchains configuration)` | `- **Java Version**: OpenJDK 21 exactly, compiled and run with --enable-preview (ScopedValue is a JDK 21 preview API); requires Maven toolchains configuration` |
| `docker/README.MD:33` | Environment variables | `- **EVITA_JAVA_OPTS** - Java commandline options. Default: none (empty string)` | `- **EVITA_JAVA_OPTS** - additional Java command-line options, appended after the mandatory --enable-preview that the entrypoint always passes. Default: none (empty string)` |
| `docker/README.MD:36-45` | Entrypoint | `exec java \ $EVITA_JAVA_OPTS \ -jar "evita/bin/evita-server.jar" \ "configDir=$EVITA_CONFIG_FILE" \ "storage.storageDirectory=$EVITA_STORAGE_DIR" \ $EVITA_ARGS` (already stale: no `-javaagent`, wrong config variable) | replace with the real `entrypoint.sh` block including `--enable-preview \` and `-javaagent:${EVITA_BIN_DIR}${EVITA_JAR_NAME} \` |
| `documentation/user/en/get-started/run-evitadb.md:10-13` | intro | `evitaDB is a Java application, and you can run it as an embedded database in any Java application or as a separate service ...` | append: `Embedded use requires Java 21 exactly and the JVM flag --enable-preview (evitaDB relies on the JDK 21 preview API ScopedValue); the Docker image passes the flag itself.` |
| `run-evitadb.md:21-30` | What platforms are supported? | vendor/architecture paragraph | add a sentence: `Only Java 21 is supported: the runtime refuses evitaDB class files on Java 22 or later, and on Java 21 without --enable-preview.` |
| `run-evitadb.md:56-76` | Package evitaDB in your application | Maven/Gradle `evita_db` dependency only | add a `<Note type="warning">` with the JVM flag for the application launcher, surefire (`<argLine>--enable-preview</argLine>`), Gradle (`tasks.withType(JavaCompile) { options.compilerArgs += "--enable-preview" }`, `tasks.withType(Test) { jvmArgs "--enable-preview" }`, `application { applicationDefaultJvmArgs = ["--enable-preview"] }`) and the IDE run configuration; mention that javac needs the flag too when it reads a preview-marked class such as `Evita`. |
| `run-evitadb.md:207-230` | Install Docker / Pull and run image | `docker run ... index.docker.io/evitadb/evitadb:latest` | no change (image carries the flag) |
| `documentation/user/en/get-started/query-our-dataset.md:44-75` | Run your own evitaDB server with our dataset | `docker run ...` | no change |
| `query-our-dataset.md:129-147` | Connect the Java client | `evita_java_driver` dependency | scenario (a): no change. Scenario (b): add `The Java driver requires Java 21 exactly and --enable-preview on the client JVM.` |
| `documentation/user/en/get-started/create-first-database.md:28-42` | Docker assumptions | `docker run ...` | no change |
| `documentation/user/en/operate/run.md:11-12` | intro | `The Docker image is based on RedHat JDK / Linux (see docker/Dockerfile) base image (Fedora family) ...` (already wrong: `Dockerfile:1` is `azul/zulu-openjdk:21-latest`) | `The Docker image is based on the Azul Zulu OpenJDK 21 image (see docker/Dockerfile) and starts the server with --enable-preview, which evitaDB requires; it is published to Docker Hub.` |
| `run.md:193-204` | Configure the evitaDB in the container | `-e "EVITA_JAVA_OPTS=-agentlib:jdwp=..."` example | keep; add after the Note at L206-209: `The entrypoint always passes --enable-preview before EVITA_JAVA_OPTS; you cannot and need not add or remove it.` |
| `run.md:238-243` | environment variables table, `EVITA_JAVA_OPTS` row | `Java commandline arguments (list of basic arguments can be found here [javase/17 java.html]), default: none (empty string)` | `Additional Java command-line arguments appended after the mandatory --enable-preview (list of options: https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html), default: none (empty string)` |
| `run.md:378-395` | Docker Compose | `EVITA_JAVA_OPTS=-agentlib:jdwp=...` | no change (add a one-line warning only if the `JDK_JAVA_OPTIONS` variant is chosen: overriding that variable removes the flag) |
| `documentation/user/en/operate/configure.md:348-354` | Command Line Arguments | `java -jar "target/evita-server.jar" "storage.storageDirectory=../data"` | `java --enable-preview -jar "target/evita-server.jar" "storage.storageDirectory=../data"` |
| `documentation/user/en/use/connectors/java.md:18-21` | Java chapter intro (`<LS to="j">`) | pointers to run-evitadb / query-our-dataset | add: `Both modes require Java 21 exactly; the embedded engine additionally needs --enable-preview on the JVM (see Run evitaDB).` (scenario (b): `both the embedded engine and the remote driver need --enable-preview`) |
| `connectors/java.md:23-44` | Java remote client | dependency snippet | scenario (b) only: add the JVM-flag note |
| `connectors/java.md:379-400` | Runtime requirements | Proxycian dependency and `--add-modules proxycian.bytebuddy` | add a preceding paragraph `JVM requirements`: Java 21 exactly, `--enable-preview` (embedded always; driver in scenario (b)), and that the flag is also needed on javac when compiling against preview-marked evitaDB classes. |
| `documentation/user/en/use/api/write-tests.md:38-56` | evita_test_support dependency | Maven/Gradle snippets, `JUnit 5` requirement | add: the test JVM must run with `--enable-preview` - surefire `<argLine>--enable-preview</argLine>`, Gradle `test { jvmArgs "--enable-preview" }`, IDE JUnit template. |
| `documentation/developer/test_guidelines.md:108-116` | Running tests in IntelliJ (JUnit template) | four `--add-opens` lines | prepend `--enable-preview` to the template block |
| `test_guidelines.md:888-905` | Performance testing | `java -jar target/benchmarks.jar`; `-f 0` for JPDA debugging | `java --enable-preview -jar target/benchmarks.jar`; note that with `-f 0` the benchmarks run in that JVM, so the flag is mandatory there; forked runs get it from `ArtificialTestRunner` |
| `documentation/user/en/operate/observe.md:22-25` | Logging (`logback.configurationFile=... as a JVM argument`) | unrelated to the flag | no change |

### C.2 Thread-pool sections (change only under a virtual-thread executor model, not from `ScopedValue` alone)

| Doc | Section | Current statement | Proposed statement (VT model) |
|---|---|---|---|
| `documentation/user/en/operate/configure.md:10-34` | full YAML snippet | `requestThreadPool` / `transactionThreadPool` / `serviceThreadPool` each with `minThreadCount: 4`, `maxThreadCount: 16`, `threadPriority: 5`, `queueSize: 100` | replace the pool keys with whatever the VT design exposes (typically a concurrency limit and a queue size; `threadPriority` has no effect on virtual threads) |
| `configure.md:399-419` | Server configuration (`<dl>`) | `requestThreadPool` "Sets limits on the core thread pool used to serve all incoming requests..."; `transactionThreadPool` "... process transactions when they're committed ..."; `serviceThreadPool` "... service tasks such as maintenance, backup ..." | describe the executors as virtual-thread based with a concurrency limit instead of a bounded pool |
| `configure.md:511-539` | Thread pool configuration | `minThreadCount` (Default 4, "at least equal to the number of machine cores"), `maxThreadCount` (Default 16, "multiple of minThreadCount"), `threadPriority` (Default 5, "for future use"), `queueSize` (Default 100, "tasks that exceed this limit will be discarded") | drop `minThreadCount`/`threadPriority`, redefine `maxThreadCount` as the maximum number of concurrently running virtual threads, keep `queueSize` semantics |
| `configure.md:340-346` | Environment Variables | example `server.requestThreadPool.minThreadCount` -> `EVITADB_SERVER_REQUESTTHREADPOOL_MINTHREADCOUNT` | pick a key that still exists after the change |
| `documentation/user/en/operate/reference/metrics.md:423-430, 443-446` (generated) | `io_evitadb_system_evita_statistics_{request,service,transaction}_max_threads` and `..._max_threads_queue_size` | "Configured threshold for the maximum number of threads ... (`server.requestThreadPool.maxThreadCount`)" | regenerate after the event classes change; the `_max_threads` gauges become concurrency limits |
| `metrics.md:457-471` (generated) | `io_evitadb_system_request_thread_pool_statistics_{active,completed,largest_pool_size,pool_core,pool_max,pool_size,queue_remaining,queued}` | ThreadPoolExecutor semantics ("core number of threads", "largest number of threads that have ever simultaneously been in the pool") | `pool_core`, `pool_max`, `largest_pool_size`, `pool_size` lose meaning for a virtual-thread executor; `active`, `completed`, `queued`, `queue_remaining` survive |
| `metrics.md:499-515` (generated) | `io_evitadb_system_transaction_thread_pool_statistics_*` | same set | same |
| `metrics.md:483-497` (generated) | `io_evitadb_system_scheduled_executor_statistics_*` | scheduled pool | unchanged unless the scheduler moves to VTs |
| `documentation/user/en/operate/reference/jfr-events.md:137-144` (generated) | `RequestThreadPoolStatisticsEvent`, `ScheduledExecutorStatisticsEvent`, `TransactionThreadPoolStatisticsEvent` | "fired on regular intervals to track ... executor statistics" | regenerate from the renamed/reshaped event classes in `evita_engine/src/main/java/io/evitadb/core/metric/event/system/` |

### C.3 Czech mirrors to regenerate (never hand-edit)

`documentation/user/cs/get-started/run-evitadb.md`, `cs/get-started/query-our-dataset.md`,
`cs/get-started/create-first-database.md` (no content change expected, listed for completeness),
`cs/operate/run.md`, `cs/operate/configure.md`, `cs/operate/reference/metrics.md`,
`cs/operate/reference/jfr-events.md`, `cs/use/connectors/java.md`, `cs/use/api/write-tests.md`.
There is no Czech mirror for `README.md`, `CLAUDE.md`, `docker/README.MD` or
`documentation/developer/**`.

---

## Blast radius

**Who is forced onto `--enable-preview` and onto JDK 21 exactly.**

- **The build.** Every module compiles with the flag (inherited from the root pom), but only source
  files that use `ScopedValue` produce preview-marked class files. Maven's own JVM, javac (via the
  toolchain), surefire forks, javadoc, JShell in the documentation tests and JMH forks all need the flag.
  Any JDK newer than 21 - as Maven's JVM, as the toolchain, in `setup-java`, in the Docker base image or
  in the benchmark image - breaks the build or the boot. The `21` in `toolchains.xml`, the eight
  `java-version: '21'` workflow lines, `azul/zulu-openjdk:21-latest` and `openjdk-21-jdk-headless`
  all turn from minimums into pins.
- **Server operators.** Docker image and `dist.zip` users get the flag from the shipped launchers and
  notice nothing, but they cannot move the container or host to a newer JDK until evitaDB itself
  migrates. Anyone who starts `evita-server.jar` by hand (as `configure.md:353` shows) must add the
  flag; without it the process dies at `main` with `UnsupportedClassVersionError ... (class file version
  65.65535)`.
- **Embedded users** (`evita_db` bundle, `evita_test_support` in tests). Affected in both scenarios:
  application launcher, test runner (surefire/Gradle), IDE run configurations, and their own javac
  whenever it reads a marked class such as the engine entry point. They are pinned to JDK 21 for as long
  as they depend on the affected evitaDB version.
- **Java driver users.** Untouched in scenario (a); in scenario (b) every driver user - including the
  shaded all-in-one driver and any framework that repackages it - inherits the full embedded-user
  burden. This is the single largest expansion of blast radius and argues for keeping `ScopedValue` out
  of `evita_api`, or for isolating it in a source file that only server-side code loads.
- **Non-JVM clients** (C#, GraphQL, REST, gRPC over the wire): unaffected.
- **Third-party bytecode tooling** on the consumer side (agents, ASM/Byte Buddy-based frameworks, code
  coverage, obfuscators) must accept minor version 65535. JaCoCo 0.8.15 does (verified above); Byte
  Buddy and ASM 9.x do (*from knowledge*); anything that rejects "unknown" versions will refuse the jars.
- **Time-boxed by design.** When evitaDB moves to JDK 25 the flag disappears, but the JDK 21 surface it
  was written against (`Carrier.call(Callable)`, `Carrier.get(Supplier)`, `runWhere`, `callWhere`,
  `getWhere`) no longer compiles; confining usage to the surviving subset now avoids a second migration.
