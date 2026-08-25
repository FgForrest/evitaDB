/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.transaction.memory;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the two source-level invariants the warm-up savepoint mechanism depends on, by scanning `evita_engine`'s own
 * sources. Both are properties of the code as a whole, which is why they are asserted here rather than at any single
 * site: neither can be enforced by a type, and both fail SILENTLY in production — a missed structure makes a rollback
 * report success while leaving its changes applied.
 *
 * 1. **Every self-layered structure declares its rollback support.** A class whose mutators resolve their own diff
 *    layer through {@link Transaction#getOrCreateTransactionalMemoryLayer(TransactionalLayerCreator)} takes a delegate
 *    branch when there is none, and that branch is what a warm-up savepoint has to rewind. It must therefore override
 *    {@link TransactionalLayerCreator#supportsWarmUpRollback()} — the runtime backstop reads that declaration, and the
 *    default is deliberately `false`, so a structure ported to the write path without one is refused at runtime. This
 *    test moves that discovery from "the first bulk load that happens to reach it" to compile-adjacent feedback.
 *
 * 2. **No new {@link Transaction#isTransactionAvailable()} gate appears in the index mutators.** The mechanism reaches
 *    a structure through the savepoint's own API, never by asking whether a transaction exists; a mutator that
 *    branches on `isTransactionAvailable()` is one that decided for itself what the non-transactional path does, and
 *    is exactly where the pre-mechanism code silently skipped journalling. The surviving uses are enumerated in
 *    {@link ApprovedTransactionAvailabilityGates#APPROVED_GATES} with the reason each is not a rollback hole; the
 *    allowlist is the point of the test, so growing it must be a deliberate act with an argument attached.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepoint
 * @see WarmUpRollbackBackstopTest
 */
@Tag(CONTRACT)
@Tag(INDEXING)
@DisplayName("Warm-up rollback source conformance")
class WarmUpRollbackConformanceTest implements EvitaTestSupport {
	/**
	 * Source root of the module the mechanism lives in — the only one the two invariants are asserted over.
	 */
	private static final String ENGINE_SOURCE_ROOT = "evita_engine/src/main/java";

	/**
	 * Matches the self-layer resolution idiom — `getOrCreateTransactionalMemoryLayer(this)`, in either its qualified
	 * or statically imported form. Only the `this` argument is matched: a call passing a FIELD resolves a layer for a
	 * different object, whose own class is where the declaration belongs, and would give this scan the wrong subject.
	 */
	private static final Pattern SELF_LAYER_RESOLUTION = Pattern.compile(
		"(?:Transaction\\.)?getOrCreateTransactionalMemoryLayer\\(\\s*this\\s*\\)"
	);

	/**
	 * Matches a call to {@link Transaction#isTransactionAvailable()} in either form, but not its import or a JavaDoc
	 * mention of it — both of which are legitimate and neither of which is a runtime branch.
	 */
	private static final Pattern TRANSACTION_AVAILABILITY_GATE = Pattern.compile(
		"(?:Transaction\\.)?isTransactionAvailable\\(\\)"
	);

	/**
	 * Matches a call opening a warm-up savepoint. The mechanism's whole enforcement story depends on there being
	 * exactly one of these, so the pattern is deliberately broad — any way of naming the method counts.
	 */
	private static final Pattern SAVEPOINT_OPENING = Pattern.compile(
		"(?:WarmUpSavepoint\\.)?open\\(\\)"
	);

	/**
	 * Matches the guard the single opening site must keep: the savepoint is opened on the branch where the
	 * transactional maintainer is absent, which is what makes "a savepoint is open" imply "no transaction".
	 */
	private static final Pattern MAINTAINER_ABSENCE_GUARD = Pattern.compile(
		"maintainer\\s*==\\s*null|maintainer\\s*!=\\s*null"
	);

	/**
	 * Matches the traffic-recording call in the mutation bracket — the one fallible step that must stay AHEAD of the
	 * savepoint opening, because a savepoint has no `finally` protecting it until the try block below the bracket.
	 */
	private static final Pattern TRAFFIC_RECORDING = Pattern.compile("recordMutation\\(");

	/**
	 * Resolves the source root of `evita_engine` from the test module's working directory, and fails loudly when it is
	 * not there. A conformance test that cannot find its subject must never pass quietly — it would report an
	 * invariant it never checked.
	 *
	 * @return the absolute path of `evita_engine/src/main/java`
	 */
	@Nonnull
	private Path engineSourceRoot() {
		final Path sourceRoot = getRootDirectory().resolve(ENGINE_SOURCE_ROOT).normalize();
		assertTrue(
			Files.isDirectory(sourceRoot),
			() -> "The engine source root was not found at " + sourceRoot + " - this scan is broken, not the code " +
				"it checks. Re-check the working directory the test runs in."
		);
		return sourceRoot;
	}

	/**
	 * Reads every `.java` file under the engine source root, keyed by its path relative to that root so violation
	 * messages read as module-relative paths rather than machine-specific absolute ones.
	 *
	 * @param sourceRoot the root to walk
	 * @return relative path to file content, for every Java source under the root
	 */
	@Nonnull
	private static Map<String, String> readJavaSources(@Nonnull Path sourceRoot) throws IOException {
		try (final Stream<Path> files = Files.walk(sourceRoot)) {
			final List<Path> javaFiles = files
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.toList();
			final Map<String, String> sources = new LinkedHashMap<>(javaFiles.size());
			for (final Path javaFile : javaFiles) {
				sources.put(
					sourceRoot.relativize(javaFile).toString().replace('\\', '/'),
					Files.readString(javaFile, StandardCharsets.UTF_8)
				);
			}
			return sources;
		}
	}

	/**
	 * Strips the line comments and block comments from a Java source so that a pattern match cannot be satisfied by
	 * prose. JavaDoc routinely names the very methods these scans look for, and counting those would make the
	 * allowlist a list of documentation edits.
	 *
	 * String literals are deliberately NOT stripped: no literal in this module contains either idiom, and stripping
	 * them properly needs a lexer this test has no reason to grow.
	 *
	 * @param source the raw source text
	 * @return the source with comments blanked out
	 */
	@Nonnull
	private static String stripComments(@Nonnull String source) {
		return source
			.replaceAll("(?s)/\\*.*?\\*/", "")
			.replaceAll("(?m)//.*$", "");
	}

	@Nested
	@DisplayName("Self-layered structures")
	class SelfLayeredStructures {

		@Test
		@DisplayName("Every structure resolving its own diff layer declares warm-up rollback support")
		void shouldDeclareWarmUpRollbackSupportOnEverySelfLayeredStructure() throws IOException {
			final Path sourceRoot = engineSourceRoot();
			final Map<String, String> sources = readJavaSources(sourceRoot);
			final List<String> resolvingFiles = new ArrayList<>();
			for (final Map.Entry<String, String> source : sources.entrySet()) {
				if (SELF_LAYER_RESOLUTION.matcher(stripComments(source.getValue())).find()) {
					resolvingFiles.add(source.getKey());
				}
			}
			assertFalse(
				resolvingFiles.isEmpty(),
				"The scan found no structure resolving its own diff layer - the scan is broken, not the code."
			);

			final List<String> violations = new ArrayList<>();
			for (final String relativePath : resolvingFiles) {
				final Class<?> subject = loadTopLevelClass(relativePath);
				if (!TransactionalLayerCreator.class.isAssignableFrom(subject)) {
					// the idiom was found in a NESTED class, whose declaration this file-to-class mapping cannot
					// reach. Never skip it silently - a nested self-layered structure is exactly as capable of
					// leaving a rollback hole, it just needs the check written differently
					fail(
						"Class " + subject.getName() + " (" + relativePath + ") resolves its own diff layer from a " +
							"nested type this scan cannot map. Extend the scan to cover it rather than exempting it."
					);
				}
				if (declaresRollbackSupport(subject)) {
					continue;
				}
				violations.add(relativePath);
			}

			assertTrue(
				violations.isEmpty(),
				() -> "These structures take a delegate branch a warm-up savepoint would have to rewind, but do not " +
					"override TransactionalLayerCreator#supportsWarmUpRollback(): " + String.join(", ", violations) +
					". Journal their delegate-branch writes and declare the support, or - if the branch writes " +
					"nothing of its own - declare it with a JavaDoc saying so."
			);
		}

		/**
		 * Loads the top-level class a source file declares, deriving its fully qualified name from the file's path
		 * relative to the source root.
		 *
		 * @param relativePath the source path relative to the module's source root
		 * @return the loaded class
		 */
		@Nonnull
		private Class<?> loadTopLevelClass(@Nonnull String relativePath) {
			final String className = relativePath
				.substring(0, relativePath.length() - ".java".length())
				.replace('/', '.');
			try {
				return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
			} catch (final ClassNotFoundException ex) {
				throw new IllegalStateException(
					"Source file " + relativePath + " maps to class " + className + ", which is not on the test " +
						"classpath - the scan's path-to-class mapping is broken.",
					ex
				);
			}
		}

		/**
		 * Reports whether the given class overrides {@link TransactionalLayerCreator#supportsWarmUpRollback()} rather
		 * than inheriting the interface's `false` default. Reflection resolves to the most specific override, so a
		 * declaration inherited from a supertype (the node interfaces declare it once for a whole tree family) counts
		 * — which is the intent: the obligation is met wherever it is stated.
		 *
		 * @param subject the class to inspect
		 * @return `true` when the class or one of its supertypes states the declaration
		 */
		private boolean declaresRollbackSupport(@Nonnull Class<?> subject) {
			try {
				return subject.getMethod("supportsWarmUpRollback").getDeclaringClass()
					!= TransactionalLayerCreator.class;
			} catch (final NoSuchMethodException ex) {
				throw new IllegalStateException(
					"Class " + subject.getName() + " implements TransactionalLayerCreator but has no " +
						"supportsWarmUpRollback() method - the interface contract changed under this test.",
					ex
				);
			}
		}
	}

	@Nested
	@DisplayName("The savepoint's single opening site")
	class SingleOpeningSite {
		/**
		 * The one source permitted to open a warm-up savepoint, and the guard it must keep doing so under.
		 */
		private static final String OPENING_SITE = "io/evitadb/core/collection/LocalMutationExecutorCollector.java";
		/**
		 * The source declaring {@link WarmUpSavepoint#open()} itself, which necessarily names the method and is not a
		 * call site. Excluded rather than matched around, because a pattern narrow enough to tell a declaration from a
		 * call is a pattern narrow enough to miss a call written slightly differently — and missing one is the failure
		 * this test exists to prevent.
		 */
		private static final String DECLARING_SITE = "io/evitadb/core/transaction/memory/WarmUpSavepoint.java";

		@Test
		@DisplayName("Only the mutation bracket opens a savepoint, and only where there is no transaction")
		void shouldOpenSavepointOnlyFromTheMutationBracketOutsideATransaction() throws IOException {
			final Path sourceRoot = engineSourceRoot();
			final Map<String, String> sources = readJavaSources(sourceRoot);
			final List<String> openingSites = new ArrayList<>();
			for (final Map.Entry<String, String> source : sources.entrySet()) {
				if (DECLARING_SITE.equals(source.getKey())) {
					continue;
				}
				if (SAVEPOINT_OPENING.matcher(stripComments(source.getValue())).find()) {
					openingSites.add(source.getKey());
				}
			}

			// EVERY exemption in the allowlist below rests on one premise: a warm-up savepoint is open only where
			// there is no transaction, which makes `!isTransactionAvailable()` unconditionally true while one is.
			// The premise holds because the sole opening site opens the savepoint on the branch where the
			// transactional maintainer is absent - and `getTransactionalLayerMaintainer() == null` is precisely
			// `!isTransactionAvailable()`. A second opening site, or this one losing its guard, would silently
			// invalidate every one of those exemptions at once, which is why the premise is asserted rather than
			// assumed
			assertEquals(
				List.of(OPENING_SITE), openingSites,
				"A warm-up savepoint may be opened from exactly one place - the root entity mutation bracket. " +
					"Every transaction-availability exemption in this test depends on it."
			);
			final String bracket = stripComments(sources.get(OPENING_SITE));
			assertTrue(
				MAINTAINER_ABSENCE_GUARD.matcher(bracket).find(),
				"The mutation bracket must keep opening the savepoint only where there is no transactional " +
					"maintainer, i.e. no transaction."
			);
		}

		@Test
		@DisplayName("Traffic recording happens before the savepoint is opened, not after")
		void shouldOpenSavepointAfterTheTrafficRecordingThatCanThrow() throws IOException {
			// A savepoint is closed by exactly one thing: the try/finally that follows the opening block. Anything
			// throwing between the two escapes without a finally, and on the warm-up path the savepoint is bound to
			// the THREAD - so it survives into the next entity, whose own open() then fails as a nested one and takes
			// down the rest of the batch. Traffic recording is the fallible step that used to sit in that window: it
			// activates a tracing block, and a tracing implementation may throw. Ordering is safe because recording
			// touches no index, executor or storage state, so nothing revertable happens before the bracket begins
			final Path sourceRoot = engineSourceRoot();
			final String bracket = stripComments(readJavaSources(sourceRoot).get(OPENING_SITE));
			final Matcher trafficRecording = TRAFFIC_RECORDING.matcher(bracket);
			assertTrue(
				trafficRecording.find(),
				"The traffic recording call was not found in the mutation bracket - this scan is broken, not the " +
					"code it checks."
			);
			final Matcher savepointOpening = SAVEPOINT_OPENING.matcher(bracket);
			assertTrue(
				savepointOpening.find(),
				"The savepoint opening was not found in the mutation bracket - this scan is broken, not the code " +
					"it checks."
			);
			assertTrue(
				trafficRecording.start() < savepointOpening.start(),
				"Nothing that can throw may sit between opening a warm-up savepoint and the try/finally that " +
					"closes it - the traffic recording must therefore stay ahead of the opening, or a tracing " +
					"failure leaks the savepoint onto the thread and fails the next entity's open()."
			);
		}
	}

	@Nested
	@DisplayName("Approved transaction-availability gates")
	class ApprovedTransactionAvailabilityGates {
		/**
		 * The source files under `io.evitadb.index` permitted to branch on
		 * {@link Transaction#isTransactionAvailable()}, each with the reason its gates are not a warm-up-rollback
		 * hole. Nothing else may.
		 *
		 * The exemptions fall into four kinds, and every entry below is one of them:
		 *
		 * - **Cache read gates.** The gate picks between a memoized value and a freshly computed one. It writes no
		 *   index state, and the memo it may fill is recomputable — a rollback re-invalidates it rather than
		 *   restoring it, which is the accepted residue of the whole mechanism.
		 * - **Cache invalidation gates.** The gate wraps `journal-the-memo; drop the memo` at the tail of a mutator
		 *   whose real writes already happened, unconditionally, above it. It is REQUIRED for `ALIVE` correctness:
		 *   inside a transaction the mutation goes to a diff layer while the memo belongs to the committed instance,
		 *   so touching it there would corrupt what concurrent readers see. Removing it would be a behaviour change,
		 *   and it can never skip journalling — a warm-up savepoint is only ever open where there is no transaction,
		 *   so the gate is always taken while one is.
		 * - **`createLayer()` factories.** Reached only from `TransactionalLayerMaintainer`, which is itself only
		 *   reachable from inside a transaction, so the `null` arm is unreachable in production. Warm-up journalling
		 *   lives on the mutators' delegate branch and never enters the factory at all.
		 * - **Node-construction flags.** The value is passed to a freshly built node as its "may own a diff layer"
		 *   flag, not used to decide what to write. A node built inside a rolled-back mutation becomes unreachable
		 *   garbage — nothing has to rewind it.
		 */
		private static final Map<String, String> APPROVED_GATES = Map.ofEntries(
			Map.entry(
				"io/evitadb/index/CatalogIndex.java",
				"createLayer() factory - CatalogIndexChanges is in-transaction dirty tracking only"
			),
			Map.entry(
				"io/evitadb/index/attribute/AttributeIndex.java",
				"createLayer() factory - AttributeIndexChanges is in-transaction dirty tracking only"
			),
			Map.entry(
				"io/evitadb/index/array/TransactionalIntArray.java",
				"createLayer() factory - the delegate branch journals through recordWarmUpSavepointTouch()"
			),
			Map.entry(
				"io/evitadb/index/array/TransactionalObjArray.java",
				"createLayer() factory - the delegate branch journals through recordWarmUpSavepointTouch()"
			),
			Map.entry(
				"io/evitadb/index/array/TransactionalComplexObjArray.java",
				"createLayer() factory - the delegate branch journals through recordWarmUpSavepointTouch()"
			),
			Map.entry(
				"io/evitadb/index/array/UnorderedLookupTree.java",
				"cache read gate in getArray() - the flattened view is memoized only for the committed instance"
			),
			Map.entry(
				"io/evitadb/index/bitmap/TransactionalBitmap.java",
				"warm-up fast path in addAll/removeAll - BOTH arms call recordWarmUpSavepointTouch() before writing, " +
					"and the gate keeps the per-record contains() scan off the bulk-ingest hot path"
			),
			Map.entry(
				"io/evitadb/index/attribute/FilterIndex.java",
				"cache read and cache invalidation gates - the inverted and range index writes above them journal " +
					"for themselves"
			),
			Map.entry(
				"io/evitadb/index/attribute/OwnerUniqueIndex.java",
				"cache read and cache invalidation gates - the tree and record-id writes above them journal for " +
					"themselves"
			),
			Map.entry(
				"io/evitadb/index/hierarchy/HierarchyIndex.java",
				"cache read and cache invalidation gates - the item, level and orphan structures journal for themselves"
			),
			Map.entry(
				"io/evitadb/index/range/RangeIndex.java",
				"cache read and cache invalidation gates - the range point tree journals for itself"
			),
			Map.entry(
				"io/evitadb/index/cardinality/ReferenceTypeCardinalityIndex.java",
				"cache read and cache invalidation gates - the cardinality map and bitmaps journal for themselves"
			),
			Map.entry(
				"io/evitadb/index/price/AbstractPriceListAndCurrencyPriceIndex.java",
				"cache read and cache invalidation gates - the indexed price ids array journals for itself"
			),
			Map.entry(
				"io/evitadb/index/bPlusTree/TransactionalElementBPlusTree.java",
				"node-construction flags for split offspring, plus one read-only assert precondition - the node " +
					"writes themselves go through WarmUpSavepoint#writeLayer"
			)
		);

		@Test
		@DisplayName("No index source outside the allowlist branches on transaction availability")
		void shouldNotBranchOnTransactionAvailabilityOutsideTheAllowlist() throws IOException {
			final Path sourceRoot = engineSourceRoot();
			final Map<String, String> sources = readJavaSources(sourceRoot);
			final List<String> unapproved = new ArrayList<>();
			final Set<String> matched = new LinkedHashSet<>();
			for (final Map.Entry<String, String> source : sources.entrySet()) {
				final String relativePath = source.getKey();
				if (!relativePath.startsWith("io/evitadb/index/")) {
					continue;
				}
				if (!TRANSACTION_AVAILABILITY_GATE.matcher(stripComments(source.getValue())).find()) {
					continue;
				}
				if (APPROVED_GATES.containsKey(relativePath)) {
					matched.add(relativePath);
				} else {
					unapproved.add(relativePath);
				}
			}

			assertTrue(
				unapproved.isEmpty(),
				() -> "These index sources branch on Transaction.isTransactionAvailable() without an approved " +
					"exemption: " + String.join(", ", unapproved) + ". Reach the warm-up savepoint through its own " +
					"API instead - or, if the gate genuinely cannot leave a rollback hole, add it to APPROVED_GATES " +
					"with the reason."
			);
			assertFalse(
				matched.isEmpty(),
				"The scan matched no allowlisted source at all - the scan is broken, not the code."
			);
		}

		@Test
		@DisplayName("The allowlist carries no entry that has since been cleaned up")
		void shouldNotRetainStaleAllowlistEntries() throws IOException {
			final Path sourceRoot = engineSourceRoot();
			final Map<String, String> sources = readJavaSources(sourceRoot);
			final List<String> stale = new ArrayList<>();
			for (final String approved : APPROVED_GATES.keySet()) {
				final String content = sources.get(approved);
				if (content == null || !TRANSACTION_AVAILABILITY_GATE.matcher(stripComments(content)).find()) {
					stale.add(approved);
				}
			}
			// an allowlist that outlives its entries stops being a record of deliberate exceptions and becomes
			// permission for the next one to be added without argument
			assertTrue(
				stale.isEmpty(),
				() -> "These allowlist entries no longer branch on transaction availability (or no longer exist) - " +
					"remove them: " + String.join(", ", stale)
			);
		}
	}

	/**
	 * Guards the two patterns themselves: a regex that silently stopped matching would make both scans above pass
	 * while checking nothing, which is the failure mode a conformance test can least afford.
	 */
	@Nested
	@DisplayName("The scan patterns")
	class ScanPatterns {

		@Test
		@DisplayName("Both idioms are matched in their qualified and statically imported forms")
		void shouldMatchBothFormsOfEachIdiom() {
			assertTrue(
				SELF_LAYER_RESOLUTION.matcher("X l = Transaction.getOrCreateTransactionalMemoryLayer(this);").find()
			);
			assertTrue(SELF_LAYER_RESOLUTION.matcher("X l = getOrCreateTransactionalMemoryLayer(this);").find());
			assertFalse(
				SELF_LAYER_RESOLUTION.matcher("getOrCreateTransactionalMemoryLayer(this.dataSource)").find(),
				"A layer resolved for a FIELD belongs to that field's class, not this one."
			);
			assertTrue(TRANSACTION_AVAILABILITY_GATE.matcher("if (Transaction.isTransactionAvailable()) {").find());
			assertTrue(TRANSACTION_AVAILABILITY_GATE.matcher("if (!isTransactionAvailable()) {").find());
		}

		@Test
		@DisplayName("A mention inside a comment is not counted as a branch")
		void shouldNotCountCommentedMentionsAsBranches() {
			final String source = """
				/**
				 * Mentions isTransactionAvailable() in prose.
				 */
				// and getOrCreateTransactionalMemoryLayer(this) in a line comment
				class Foo {}
				""";
			final String stripped = stripComments(source);
			assertFalse(TRANSACTION_AVAILABILITY_GATE.matcher(stripped).find());
			assertFalse(SELF_LAYER_RESOLUTION.matcher(stripped).find());
		}
	}

}
