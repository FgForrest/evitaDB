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

package io.evitadb.index.fulltext.typo;

import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import io.evitadb.test.fulltext.CzechLexicon;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker;
import io.evitadb.test.fulltext.LevenshteinDictionaryWalker.Hit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof of concept: a Levenshtein automaton from {@code lucene-core} drives a guided walk over evitaDB's own
 * {@link TransactionalBucketBPlusTree} holding a real Czech vocabulary (the hunspell {@code cs_CZ.dic} test fixture,
 * lowercased and diacritics-folded to mirror the index-side analyzer chain of this branch).
 *
 * Retains the prototype's seek/read counters, timing and read-ahead checks. Engine correctness belongs to
 * `FuzzyDictionaryWalkTest`; these cost checks remain slow and are run only when explicitly scheduled.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Prototype fuzzy dictionary walk cost and read-ahead measurements")
@Tag(ENGINE)
@Tag(io.evitadb.test.TestTags.SLOW)
@Tag(FULLTEXT)
class FuzzyDictionaryWalkMeasurementTest {
	// odd on purpose: the tree derives its minimum block size as half of this and requires it to be strictly less
	private static final int LEAF_BLOCK_SIZE = 63;
	private static TransactionalBucketBPlusTree<String> dictionary;
	private static int dictionarySize;

	/** Loads the prototype vocabulary once for an explicitly scheduled measurement run. */
	@BeforeAll
	static void loadDictionary() {
		// lowercased and diacritics-folded, mirroring the index-side analyzer chain of this branch
		final Set<String> words = CzechLexicon.load(true);
		dictionary = new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
		int pk = 1;
		for (final String word : words) {
			// the walker assumes natural String order equals code point order, which holds inside the BMP
			assertTrue(word.codePointCount(0, word.length()) == word.length(), "BMP only: " + word);
			dictionary.addRecord(word, pk++);
		}
		dictionarySize = words.size();
		assertTrue(dictionarySize > 200_000, "expected a real-sized vocabulary, got " + dictionarySize);
	}

	/** Cost of the walk. */
	@Nested
	@DisplayName("Cost of the walk")
	class Cost {

		/** With a frozen first letter and one edit the walk reads well under one percent of the keys. */
		@Test
		@DisplayName("With a frozen first letter and one edit the walk reads well under one percent of the keys")
		void shouldReadSmallFractionAtDistanceOne() {
			final LevenshteinDictionaryWalker walker = new LevenshteinDictionaryWalker("kalhoty", 1, 1);
			final long start = System.nanoTime();
			final List<Hit> hits = walker.walk(dictionary);
			final long nanos = System.nanoTime() - start;
			report("kalhoty", 1, 1, walker, hits, nanos);
			assertTrue(
				walker.getReads() * 100 < dictionarySize,
				"reads " + walker.getReads() + " of " + dictionarySize
			);
		}

		/** With two edits the walk still reads only a few percent of the keys. */
		@Test
		@DisplayName("With two edits the walk still reads only a few percent of the keys")
		void shouldReadSmallFractionAtDistanceTwo() {
			final LevenshteinDictionaryWalker walker = new LevenshteinDictionaryWalker("kalhoty", 2, 1);
			final long start = System.nanoTime();
			final List<Hit> hits = walker.walk(dictionary);
			final long nanos = System.nanoTime() - start;
			report("kalhoty", 2, 1, walker, hits, nanos);
			assertTrue(
				walker.getReads() * 20 < dictionarySize,
				"reads " + walker.getReads() + " of " + dictionarySize
			);
		}

		/** Without a frozen prefix the walk is still guided, not a scan. */
		@Test
		@DisplayName("Without a frozen prefix the walk is still guided, not a scan")
		void shouldStayGuidedWithoutFrozenPrefix() {
			final LevenshteinDictionaryWalker walker = new LevenshteinDictionaryWalker("kalhoty", 2, 0);
			final long start = System.nanoTime();
			final List<Hit> hits = walker.walk(dictionary);
			final long nanos = System.nanoTime() - start;
			report("kalhoty", 2, 0, walker, hits, nanos);
			assertTrue(
				walker.getReads() * 4 < dictionarySize,
				"reads " + walker.getReads() + " of " + dictionarySize
			);
		}

		/** Read-ahead sized to the leaf block resolves most rejections without a root descent. */
		@ParameterizedTest(name = "`{0}` maxEdits={1} frozenPrefix={2}")
		@CsvSource({"kalhoty, 1, 1", "kalhoty, 2, 1", "kalhoty, 2, 0", "cerny, 2, 1", "bnuda, 2, 0"})
		@DisplayName("Read-ahead sized to the leaf block resolves most rejections without a root descent")
		void shouldTradeSeeksForSequentialReads(String query, int maxEdits, int frozenPrefix) {
			final LevenshteinDictionaryWalker plain = new LevenshteinDictionaryWalker(query, maxEdits, frozenPrefix);
			final LevenshteinDictionaryWalker ahead =
				new LevenshteinDictionaryWalker(query, maxEdits, frozenPrefix, LEAF_BLOCK_SIZE);
			// warm both paths once so the comparison is not dominated by class loading and JIT
			plain.walk(dictionary);
			ahead.walk(dictionary);
			final long plainStart = System.nanoTime();
			final List<Hit> plainHits = plain.walk(dictionary);
			final long plainNanos = System.nanoTime() - plainStart;
			final long aheadStart = System.nanoTime();
			final List<Hit> aheadHits = ahead.walk(dictionary);
			final long aheadNanos = System.nanoTime() - aheadStart;
			assertEquals(plainHits, aheadHits);
			System.out.printf(
				Locale.ROOT,
				"fuzzy `%s` maxEdits=%d frozenPrefix=%d: plain %d reads / %d seeks / %.2f ms; " +
					"read-ahead(%d) %d reads / %d seeks / %d resolved by read-ahead / %.2f ms%n",
				query, maxEdits, frozenPrefix,
				plain.getReads(), plain.getSeeks(), plainNanos / 1_000_000.0,
				LEAF_BLOCK_SIZE, ahead.getReads(), ahead.getSeeks(), ahead.getReadAheadHits(), aheadNanos / 1_000_000.0
			);
			assertTrue(
				ahead.getSeeks() * 2 < plain.getSeeks(),
				"expected read-ahead to remove most seeks: " + ahead.getSeeks() + " vs " + plain.getSeeks()
			);
		}

		/** The linear scan is the baseline the walk is measured against. */
		@Test
		@DisplayName("The linear scan is the baseline the walk is measured against")
		void shouldMeasureScanBaseline() {
			final LevenshteinDictionaryWalker walker = new LevenshteinDictionaryWalker("kalhoty", 2, 1);
			// warm up
			walker.scan(dictionary);
			walker.walk(dictionary);
			final long scanStart = System.nanoTime();
			final List<String> scanned = walker.scan(dictionary);
			final long scanNanos = System.nanoTime() - scanStart;
			final long walkStart = System.nanoTime();
			final List<Hit> walked = walker.walk(dictionary);
			final long walkNanos = System.nanoTime() - walkStart;
			assertEquals(scanned, walked.stream().map(Hit::term).toList());
			System.out.printf(
				Locale.ROOT,
				"fuzzy `kalhoty` maxEdits=2 frozenPrefix=1: scan of %d keys %.2f ms; walk %d reads %.2f ms%n",
				dictionarySize, scanNanos / 1_000_000.0, walker.getReads(), walkNanos / 1_000_000.0
			);
		}

		/** Sequential read-ahead changes the cost profile, never the result. */
		@ParameterizedTest(name = "`{0}` maxEdits={1} frozenPrefix={2}")
		@CsvSource({"cerny, 2, 1", "kalhoty, 2, 0", "bnuda, 1, 1", "xqzv, 2, 0", "a, 2, 0"})
		@DisplayName("Sequential read-ahead changes the cost profile, never the result")
		void shouldReturnSameSetWithReadAhead(String query, int maxEdits, int frozenPrefix) {
			final List<Hit> plain = new LevenshteinDictionaryWalker(query, maxEdits, frozenPrefix).walk(dictionary);
			for (final int readAhead : new int[]{1, 8, LEAF_BLOCK_SIZE, 4 * LEAF_BLOCK_SIZE}) {
				final List<Hit> withReadAhead =
					new LevenshteinDictionaryWalker(query, maxEdits, frozenPrefix, readAhead).walk(dictionary);
				assertEquals(plain, withReadAhead, "readAhead=" + readAhead);
			}
		}

		/** Reports the prototype counters and elapsed time of the explicitly scheduled walk. */
		private void report(
			@Nonnull String query, int maxEdits, int frozenPrefix,
			@Nonnull LevenshteinDictionaryWalker walker, @Nonnull List<Hit> hits, long nanos
		) {
			System.out.printf(
				Locale.ROOT,
				"fuzzy `%s` maxEdits=%d frozenPrefix=%d: %d hits, %d reads (%.2f %% of %d keys), %d seeks, %.2f ms%n",
				query, maxEdits, frozenPrefix, hits.size(), walker.getReads(),
				walker.getReads() * 100.0 / dictionarySize, dictionarySize, walker.getSeeks(), nanos / 1_000_000.0
			);
			System.out.println("  hits: " + hits.stream().map(h -> h.term() + "@" + h.distance()).toList());
		}
	}
}
