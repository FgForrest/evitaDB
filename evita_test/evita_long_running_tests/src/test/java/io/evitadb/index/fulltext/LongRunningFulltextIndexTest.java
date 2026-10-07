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

package io.evitadb.index.fulltext;

import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Generational randomized proof for {@link FulltextIndex}, run against {@link FulltextIndexModel}, an oracle derived
 * from the indexed VALUES rather than from the index.
 *
 * ## What this covers that the dictionary's own generational tests cannot
 *
 * The dictionary is a {@link io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree}, whose own proofs cover its
 * record sets and its impact column. What sits ABOVE it, and what this test drives:
 *
 * - **add/remove symmetry.** A value fans out into many (field, term) postings, an impact each, and a length. It has
 *   to give back exactly those on the way out, including when it was an array removed in a different element order.
 * - **the field registry.** Fields are registered with their own pivots, retired, and registered anew under the same
 *   key with a fresh id, inside transactions. The registry lives in the index's own layer, and a commit has to
 *   publish it together with the dictionary and the length tables.
 * - **the dirty gate.** A version no transaction wrote to is carried forward as the same instance. A write that
 *   failed to create the layer would publish nothing and silently lose itself; the committed version is compared
 *   with the model, never with itself, so the loss shows up.
 * - **copy-on-write.** After every commit the version that was WRITTEN TO is checked against its own
 *   pre-transaction content. It shares leaves, posting lists and length blocks with the version just published, so
 *   anything mutated in place shows up as the older version answering differently.
 *
 * The chain runs in epochs: once the model holds `MAX_FIELD_COUNT` fields, the next generation starts over with an
 * empty index, so retirement keeps happening through the whole run while the field registry stays small.
 *
 * The primary keys span two 65,536-key blocks, so the length tables hold two blocks and the long posting lists two
 * bitmap containers - which is where an impact column chunked per container could lose its alignment.
 *
 * ## Calibration
 *
 * Measured against two counterfactuals on 2026-10-07, each in `FulltextIndex#unindexValues`:
 *
 * - **dropping the length-table removal** failed both proofs in their first generation (0.1 s), when the next write
 *   to the entity hit the index's own "already has a value" guard;
 * - **dropping the `markWritten()` call**, so a transaction that only removes values creates no layer of the index,
 *   failed the transactional proof in 0.5 s at the commit's layer sweep, and left the warm-up proof green - outside
 *   a transaction there is no layer to forget.
 *
 * Whoever next changes how a value is indexed or removed, or how a field is registered or retired, owes this test
 * that check again: if it survives its own counterfactual, it has stopped testing anything.
 *
 * ```
 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
 *     -Dtest=LongRunningFulltextIndexTest
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext index (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
class LongRunningFulltextIndexTest implements TimeBoundedTestSupport {
	/**
	 * The field keys the steps draw from: every kind of field, and two attributes, so one generation can write
	 * several fields of one kind.
	 */
	static final FulltextFieldKey[] KEYS = {
		FulltextFieldKey.attribute("name"),
		FulltextFieldKey.attribute("description"),
		FulltextFieldKey.associatedData("body"),
		FulltextFieldKey.referenceAttribute("brand", "label")
	};
	/**
	 * The primary keys the steps draw from: 120 in the first 65,536-key block and 120 in the second.
	 */
	static final int[] PRIMARY_KEYS = primaryKeys();
	/**
	 * The field count at which the chain starts a new epoch.
	 */
	private static final int MAX_FIELD_COUNT = 16;
	/**
	 * The most steps one transaction takes.
	 */
	private static final int MAX_OPERATIONS_PER_TRANSACTION = 40;

	/**
	 * Registry providing the real Czech index-slot analyzer.
	 */
	private static FulltextAnalyzerRegistry registry;
	/**
	 * The analyzer every index in this test uses.
	 */
	private static FulltextAnalyzer analyzer;

	@BeforeAll
	static void setUpAnalyzer() {
		registry = new FulltextAnalyzerRegistry();
		analyzer = registry.getIndexAnalyzer("product", Locale.forLanguageTag("cs"));
	}

	@AfterAll
	static void closeRegistry() {
		registry.close();
	}

	@DisplayName("survives randomized value writes, field registrations and retirements across chained commits")
	@ParameterizedTest(name = "FulltextIndex should survive generational randomized churn against a value oracle")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			100,
			new TestState(newModel(), new FulltextIndex(analyzer), new StringBuilder(1_024)),
			(random, state) -> {
				final boolean newEpoch = state.model().getFieldCount() >= MAX_FIELD_COUNT;
				final FulltextIndexModel before = newEpoch ? newModel() : state.model();
				final FulltextIndex index = newEpoch ? new FulltextIndex(analyzer) : state.index();
				final String expectedBefore = before.expectedContent();
				final FulltextIndexModel model = before.copy();
				final StringBuilder log = state.log();
				log.setLength(0);
				final AtomicReference<FulltextIndex> published = new AtomicReference<>();

				assertStateAfterCommit(
					index,
					original -> {
						model.applyRandomOperations(
							original, random, 1 + random.nextInt(MAX_OPERATIONS_PER_TRANSACTION),
							MAX_FIELD_COUNT, log
						);
						assertEquals(
							model.expectedContent(), model.contentOf(original),
							() -> "The transaction does not read its own writes.\n" + log
						);
					},
					(original, committed) -> {
						final FulltextIndex result = committed == null ? original : committed;
						final String expected = model.expectedContent();
						if (result == original) {
							// carried forward as the same instance, which is legal only when the batch changed
							// nothing - the other way to get here is a write that never created the layer
							assertEquals(
								expectedBefore, expected,
								() -> "No new version was published, yet the model changed - a write did not " +
									"create the index's layer.\n" + log
							);
						}
						assertEquals(expected, model.contentOf(result), () -> "The published version.\n" + log);
						// the previous version shares structure with the published one, so an in-place write shows
						// up here
						assertEquals(
							expectedBefore, before.contentOf(original),
							() -> "The version that was written to.\n" + log
						);
						published.set(result);
					}
				);

				return new TestState(model, published.get(), log);
			},
			(state, throwable) -> System.out.println("Last generation: " + state.log())
		);
	}

	@DisplayName("survives randomized value writes, field registrations and retirements outside a transaction")
	@ParameterizedTest(name = "FulltextIndex should survive non-transactional (warm-up) churn against a value oracle")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalWarmUpProofTest(@Nonnull GenerationalTestInput input) {
		runFor(
			input,
			100,
			new TestState(newModel(), new FulltextIndex(analyzer), new StringBuilder(1_024)),
			(random, state) -> {
				final boolean newEpoch = state.model().getFieldCount() >= MAX_FIELD_COUNT;
				final FulltextIndexModel model = newEpoch ? newModel() : state.model();
				final FulltextIndex index = newEpoch ? new FulltextIndex(analyzer) : state.index();
				final StringBuilder log = state.log();
				log.setLength(0);

				model.applyRandomOperations(
					index, random, 1 + random.nextInt(MAX_OPERATIONS_PER_TRANSACTION), MAX_FIELD_COUNT, log
				);
				assertEquals(model.expectedContent(), model.contentOf(index), () -> "The index.\n" + log);

				return new TestState(model, index, log);
			},
			(state, throwable) -> System.out.println("Last generation: " + state.log())
		);
	}

	/**
	 * Creates an empty model over the test's analyzer, keys and primary keys.
	 *
	 * @return the model
	 */
	@Nonnull
	private static FulltextIndexModel newModel() {
		return new FulltextIndexModel(analyzer, KEYS, PRIMARY_KEYS);
	}

	/**
	 * Builds the primary keys the steps draw from.
	 *
	 * @return 120 keys at the start of the first 65,536-key block and 120 at the start of the second
	 */
	@Nonnull
	private static int[] primaryKeys() {
		final int[] primaryKeys = new int[240];
		for (int i = 0; i < 120; i++) {
			primaryKeys[i] = i;
			primaryKeys[120 + i] = 65_536 + i;
		}
		return primaryKeys;
	}

	/**
	 * The state carried from one generation to the next.
	 *
	 * @param model the model of the last published version
	 * @param index the last published version
	 * @param log   the steps of the generation, for the failure report
	 */
	private record TestState(
		@Nonnull FulltextIndexModel model,
		@Nonnull FulltextIndex index,
		@Nonnull StringBuilder log
	) {}

}
