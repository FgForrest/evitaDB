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
import io.evitadb.index.fulltext.typo.LevenshteinDictionaryWalker.Hit;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof of concept: a Levenshtein automaton from {@code lucene-core} drives a guided walk over evitaDB's own
 * {@link TransactionalBucketBPlusTree} holding a real Czech vocabulary (the hunspell {@code cs_CZ.dic} test fixture,
 * lowercased and diacritics-folded to mirror the index-side analyzer chain of this branch).
 *
 * The walk is checked three ways: it must return exactly what a linear scan with the same acceptor returns; every hit
 * must carry the distance the reference DP computes; and it must read only a small fraction of the dictionary, which
 * is the property that makes the approach viable at all.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fuzzy term lookup by a Levenshtein automaton walking an evitaDB B+ tree")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FuzzyDictionaryWalkTest {
	private static final String DICTIONARY_RESOURCE = "/fulltext/hunspell/cs_CZ.dic";
	// odd on purpose: the tree derives its minimum block size as half of this and requires it to be strictly less
	private static final int LEAF_BLOCK_SIZE = 63;
	private static TransactionalBucketBPlusTree<String> dictionary;
	private static int dictionarySize;

	@BeforeAll
	static void loadDictionary() throws IOException {
		final Set<String> words = new TreeSet<>();
		try (
			final InputStream stream = FuzzyDictionaryWalkTest.class.getResourceAsStream(DICTIONARY_RESOURCE);
			final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
		) {
			// the first line of a .dic file is the entry count
			reader.readLine();
			String line;
			while ((line = reader.readLine()) != null) {
				final int flagsSeparator = line.indexOf('/');
				final String word = (flagsSeparator < 0 ? line : line.substring(0, flagsSeparator))
					.trim()
					.toLowerCase(Locale.ROOT);
				if (word.isEmpty() || !word.chars().allMatch(Character::isLetter)) {
					continue;
				}
				final String folded = fold(word);
				// the walker assumes natural String order equals code point order, which holds inside the BMP
				assertTrue(folded.codePointCount(0, folded.length()) == folded.length(), "BMP only: " + word);
				words.add(folded);
			}
		}
		dictionary = new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, String.class);
		int pk = 1;
		for (final String word : words) {
			dictionary.addRecord(word, pk++);
		}
		dictionarySize = words.size();
		assertTrue(dictionarySize > 200_000, "expected a real-sized vocabulary, got " + dictionarySize);
	}

	/**
	 * Mirrors {@code ASCIIFoldingFilter} placed at the end of the index-side chain.
	 */
	@Nonnull
	private static String fold(@Nonnull String word) {
		final char[] input = word.toCharArray();
		final char[] output = new char[input.length * 4];
		final int length = ASCIIFoldingFilter.foldToASCII(input, 0, output, 0, input.length);
		return new String(output, 0, length);
	}

	@Nested
	@DisplayName("Correctness against the linear scan")
	class Correctness {

		@ParameterizedTest(name = "`{0}` maxEdits={1} frozenPrefix={2}")
		@CsvSource({
			"cerny, 1, 1",
			"cerny, 2, 1",
			"cerny, 2, 0",
			"bunda, 1, 1",
			"bunda, 2, 1",
			"kalhoty, 2, 1",
			"mikina, 1, 0",
			"pansky, 2, 1",
			"stul, 1, 1",
			"zidle, 2, 1",
			"dum, 1, 1",
			"kabat, 2, 0",
			// a mistyped query: transposition inside the word
			"bnuda, 1, 1",
			// a query that is not a dictionary word at all
			"xqzv, 2, 0"
		})
		@DisplayName("The guided walk returns exactly the set the acceptor accepts on a full scan")
		void shouldReturnExactlyTheScannedSet(String query, int maxEdits, int frozenPrefix) {
			final LevenshteinDictionaryWalker walker = new LevenshteinDictionaryWalker(query, maxEdits, frozenPrefix);
			final List<String> expected = walker.scan(dictionary);
			final List<String> actual = walker.walk(dictionary).stream().map(Hit::term).toList();
			assertEquals(expected, actual);
		}

		@ParameterizedTest(name = "`{0}` maxEdits={1}")
		@CsvSource({"cerny, 2", "kalhoty, 2", "pansky, 2", "bnuda, 2"})
		@DisplayName("Every hit carries the distance the reference DP computes")
		void shouldClassifyEveryHitCorrectly(String query, int maxEdits) {
			final LevenshteinDictionaryWalker walker = new LevenshteinDictionaryWalker(query, maxEdits, 1);
			final List<Hit> hits = walker.walk(dictionary);
			assertTrue(hits.size() > 1, "expected several neighbours for `" + query + "`");
			for (final Hit hit : hits) {
				assertEquals(
					LevenshteinAutomatonStandaloneTest.osaDistance(query, hit.term()),
					hit.distance(),
					"distance of `" + hit.term() + "` from `" + query + "`"
				);
			}
		}

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

		@Test
		@DisplayName("A mistyped word finds its intended dictionary word at distance one")
		void shouldFindIntendedWordForTransposition() {
			final List<Hit> hits = new LevenshteinDictionaryWalker("bnuda", 1, 1).walk(dictionary);
			assertTrue(
				hits.stream().anyMatch(hit -> hit.term().equals("bunda") && hit.distance() == 1),
				"expected `bunda` at distance 1 among " + hits
			);
		}
	}

	@Nested
	@DisplayName("Cost of the walk")
	class Cost {

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
