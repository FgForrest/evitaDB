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

package io.evitadb.index.trigram;

import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized churn proof for {@link TrigramIndex}, run against an oracle built from the CORPUS rather
 * than from the index.
 *
 * # What this covers that the tree's own generational tests cannot
 *
 * The index stores its postings in a {@link TrigramPostingStore}, which is a transactional B+ tree with its own
 * generational and savepoint proofs. Those establish that the tree versions and restores its MAPPING correctly, and
 * nothing here re-establishes it. What sits ABOVE the tree, and is what this drives:
 *
 * - **add/remove symmetry.** One value fans out into many trigrams on the way in and has to give back exactly those
 *   on the way out. Nothing but a corpus-derived oracle can see an asymmetry between the two extractions - an oracle
 *   read from the index's own postings agrees with itself whichever way they were built.
 * - **key life cycle.** A trigram whose last value dies leaves the table outright, and a value born later resurrects
 *   the key. Churn is the only thing that reaches the delete-then-reinsert path at all.
 * - **the dirty gate.** {@link TrigramIndex#createCopyWithMergedTransactionalMemory} carries an untouched index
 *   forward BY REFERENCE. A mutation that fails to mark the index dirty publishes nothing and silently loses the
 *   write - caught here because the committed version is compared against the corpus, not against itself.
 * - **postings are shared, so they must never be mutated in place.** After each commit the version that was WRITTEN
 *   TO is re-checked against its own pre-transaction content. It shares posting instances by reference with the
 *   version just published, so an in-place mutation anywhere in {@link TrigramPostings} shows up as the older version
 *   answering differently than it did before - which is the invariant {@link TrigramPostingStore} rests on and the
 *   one a tree fuzz structurally cannot reach, since a tree test stores immutable values.
 *
 * # What the oracle does NOT prove
 *
 * It shares {@link TrigramCodec#extractUniqueTrigrams} with the implementation, so a defect in trigram EXTRACTION
 * itself is invisible here - both sides would be wrong together. That is `TrigramCodecTest`'s job, and it is why
 * this test is worth no more than the codec's own coverage.
 *
 * # Calibration
 *
 * A sweep that cannot fail is decorative, so this one was measured against its own counterfactual on 2026-09-01:
 * dropping the LAST trigram of every value in {@link TrigramIndex#valueRemoved} - precisely the add/remove asymmetry
 * the corpus oracle exists to see - failed it inside the first hundred generations, at 138 operations, before a
 * single progress dot, with `The published version holds 47 trigram keys, the corpus implies 45`. An unmodified run
 * of the same minute completes roughly 472 000 generations. Whoever next changes how a value's trigrams are added or
 * removed owes this test that check again: if it survives its own counterfactual, it has stopped testing anything.
 *
 * ```
 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
 *     -Dtest=LongRunningTrigramIndexTest
 * ```
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
class LongRunningTrigramIndexTest implements TimeBoundedTestSupport {
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "name", null);
	/**
	 * How many distinct letters a generated value may use. Deliberately tiny: a small alphabet is what makes
	 * trigrams collide across values often enough for postings to grow past
	 * {@link TrigramPostings#SMALL_POSTING_THRESHOLD} and promote, and for churn to demote them back.
	 */
	private static final int ALPHABET = 4;
	/**
	 * The live corpus size the batch steers towards. Chosen so the busiest trigrams sit either side of the
	 * promotion threshold rather than comfortably above it, which is where the representation switch is exercised.
	 */
	private static final int MAX_LIVE_VALUES = 400;
	private static final int MAX_OPERATIONS_PER_TRANSACTION = 100;
	/**
	 * The shortest value a batch may generate - BELOW {@link TrigramCodec#MINIMAL_INDEXABLE_LENGTH}, on purpose, so
	 * that values contributing no trigram at all are created and removed like any other.
	 */
	private static final int MIN_VALUE_LENGTH = 2;
	private static final int MAX_VALUE_LENGTH = 9;

	@ParameterizedTest(name = "TrigramIndex should survive generational randomized churn against a corpus oracle")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Map<Integer, String> corpus = new HashMap<>();
		final Set<String> liveValues = new HashSet<>();
		// never reused, exactly as the shared value tree never reuses one - a value that dies and is written again
		// comes back under a NEW id, which is what makes the resurrection path realistic
		final AtomicInteger nextValueId = new AtomicInteger(1);

		runFor(
			input,
			100,
			new TestState(new StringBuilder(), new TrigramIndex(ATTRIBUTE_KEY)),
			(random, testState) -> {
				final TrigramIndex index = testState.index();
				final AtomicReference<TrigramIndex> committedResult = new AtomicReference<>();

				final StringBuilder codeBuffer = testState.code();
				codeBuffer
					.append("final TrigramIndex index = new TrigramIndex(ATTRIBUTE_KEY);\n")
					.append(
						corpus.entrySet().stream()
							.map(it -> "index.valueCreated(" + it.getKey() + ", \"" + it.getValue() + "\");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");

				// the content this version must STILL answer once the commit has published a new one
				final Map<Long, List<Integer>> publishedBefore = expectedPostings(corpus);

				assertStateAfterCommit(
					index,
					original -> applyRandomBatch(random, original, corpus, liveValues, nextValueId, codeBuffer),
					(original, committed) -> {
						final Map<Long, List<Integer>> expected = expectedPostings(corpus);
						if (committed == null) {
							// nothing was published, which is legal only when the batch moved no trigram at all -
							// every value it touched was below the minimal indexable length. Asserted rather than
							// assumed, because the other way for a version not to be published is a mutation that
							// forgot to mark the index dirty, and that one loses writes
							assertEquals(
								publishedBefore, expected,
								"No new index version was published, yet the corpus's trigrams moved - a mutation " +
									"did not mark the index dirty.\n" + codeBuffer
							);
						}
						final TrigramIndex published = committed == null ? original : committed;
						assertMatchesCorpus(published, expected, "The published version", codeBuffer);
						// the previously published version shares posting instances with the one just built, so this
						// is where an in-place mutation of a posting surfaces
						assertMatchesCorpus(
							original, publishedBefore, "The version that was written to", codeBuffer
						);
						committedResult.set(published);
					}
				);

				return new TestState(new StringBuilder(), committedResult.get());
			},
			(testState, throwable) -> System.out.println(testState.code())
		);
	}

	/**
	 * Applies a random batch of value creations and removals to `index`, mirroring each one into the
	 * `corpus` / `liveValues` reference model so the two stay in lockstep.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull TrigramIndex index,
		@Nonnull Map<Integer, String> corpus,
		@Nonnull Set<String> liveValues,
		@Nonnull AtomicInteger nextValueId,
		@Nonnull StringBuilder codeBuffer
	) {
		try {
			final int operationsInTransaction = random.nextInt(MAX_OPERATIONS_PER_TRANSACTION);
			for (int i = 0; i < operationsInTransaction; i++) {
				final int live = corpus.size();
				if ((random.nextBoolean() || live < 10) && live < MAX_LIVE_VALUES) {
					// the value tree holds each distinct value once, so a value already live cannot be created again -
					// the length is redrawn along with the word, which is what keeps this loop finite even when every
					// word of the shortest length happens to be taken
					String value;
					do {
						value = randomWord(random, MIN_VALUE_LENGTH + random.nextInt(MAX_VALUE_LENGTH - MIN_VALUE_LENGTH + 1));
					} while (liveValues.contains(value));

					final int valueId = nextValueId.getAndIncrement();
					corpus.put(valueId, value);
					liveValues.add(value);

					codeBuffer.append("index.valueCreated(").append(valueId).append(", \"").append(value).append("\");\n");
					index.valueCreated(valueId, value);
				} else {
					final Iterator<Entry<Integer, String>> it = corpus.entrySet().iterator();
					Entry<Integer, String> valueToRemove = null;
					final int itemToRemove = random.nextInt(live);
					for (int j = 0; j < itemToRemove + 1; j++) {
						valueToRemove = it.next();
					}
					it.remove();
					liveValues.remove(valueToRemove.getValue());

					codeBuffer.append("index.valueRemoved(").append(valueToRemove.getKey()).append(", \"")
						.append(valueToRemove.getValue()).append("\");\n");
					index.valueRemoved(valueToRemove.getKey(), valueToRemove.getValue());
				}
			}
		} catch (Exception ex) {
			fail("\n" + codeBuffer, ex);
		}
	}

	/**
	 * Asserts that `index` holds exactly `expected` and nothing besides.
	 *
	 * The key COUNT is what rules out extra trigrams: every expected key is then checked by content, so a table
	 * agreeing on both holds precisely the expected set - no enumeration of the index's own keys is needed, and
	 * asking it for them would weaken the oracle by letting the index nominate what gets compared.
	 *
	 * @param index      the version to check
	 * @param expected   the postings the corpus implies
	 * @param label      which version is being checked, named in the failure
	 * @param codeBuffer the reproduction script of this generation
	 */
	private static void assertMatchesCorpus(
		@Nonnull TrigramIndex index,
		@Nonnull Map<Long, List<Integer>> expected,
		@Nonnull String label,
		@Nonnull StringBuilder codeBuffer
	) {
		assertEquals(
			expected.size(), index.getTrigramCount(),
			() -> label + " holds " + index.getTrigramCount() + " trigram keys, the corpus implies " +
				expected.size() + ".\n" + codeBuffer
		);
		for (final Entry<Long, List<Integer>> entry : expected.entrySet()) {
			assertArrayEquals(
				toIntArray(entry.getValue()),
				index.getValueIdsOf(entry.getKey()).getArray(),
				() -> label + " answers the wrong posting for trigram `" +
					TrigramCodec.toDisplayString(entry.getKey()) + "`.\n" + codeBuffer
			);
		}
	}

	/**
	 * Builds the posting table the corpus implies: every trigram mapped to the ascending ids of the values whose
	 * text contains it. This is the oracle - derived from the values themselves, never from the index.
	 *
	 * @param corpus the live `value id -> value` model
	 * @return trigram to its ascending value ids
	 */
	@Nonnull
	private static Map<Long, List<Integer>> expectedPostings(@Nonnull Map<Integer, String> corpus) {
		final Map<Long, List<Integer>> postings = new HashMap<>();
		for (final Entry<Integer, String> entry : corpus.entrySet()) {
			for (final long trigram : TrigramCodec.extractUniqueTrigrams(entry.getValue())) {
				postings.computeIfAbsent(trigram, it -> new ArrayList<>()).add(entry.getKey());
			}
		}
		// the index answers ascending, and the corpus is walked in hash order
		for (final List<Integer> valueIds : postings.values()) {
			valueIds.sort(null);
		}
		return postings;
	}

	/**
	 * @param rnd    the workload's RNG
	 * @param length the word's length
	 * @return a random lower-case word over {@link #ALPHABET} letters
	 */
	@Nonnull
	private static String randomWord(@Nonnull Random rnd, int length) {
		final StringBuilder word = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			word.append((char) ('a' + rnd.nextInt(ALPHABET)));
		}
		return word.toString();
	}

	/**
	 * @param values the ascending value ids
	 * @return them as a primitive array, which is what the index answers with
	 */
	@Nonnull
	private static int[] toIntArray(@Nonnull List<Integer> values) {
		final int[] array = new int[values.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = values.get(i);
		}
		return array;
	}

	private record TestState(
		StringBuilder code,
		TrigramIndex index
	) {}

}
