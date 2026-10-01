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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Expansion;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.MergeStrategy;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Random;
import java.util.TreeSet;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the phase-1 scorer: the counting rule, the lane composition and ordering, the top-N selection, and that
 * the linear and the galloping merge are interchangeable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Fulltext phase-1 scorer")
class FulltextPhaseOneScorerTest {

	/**
	 * Builds an expansion from postings and their impacts given as ints.
	 *
	 * @param distance the edit distance
	 * @param pairs    posting, impact, posting, impact, ...
	 * @return the expansion
	 */
	@Nonnull
	private static Expansion expansion(int distance, @Nonnull int... pairs) {
		final int[] postings = new int[pairs.length / 2];
		final byte[] impacts = new byte[pairs.length / 2];
		for (int i = 0; i < postings.length; i++) {
			postings[i] = pairs[2 * i];
			impacts[i] = (byte) pairs[2 * i + 1];
		}
		return new Expansion(postings, impacts, distance);
	}

	/**
	 * Reads one lane out of a composite.
	 *
	 * @param composite the composite
	 * @param shift     the lane's bit offset
	 * @return the lane's unsigned byte value
	 */
	private static int lane(long composite, int shift) {
		return (int) ((composite >>> shift) & 0xFFL);
	}

	@Nested
	@DisplayName("Counting rule")
	class CountingRule {

		@Test
		@DisplayName("A token counts once however many of its expansions hit, keeping the best lanes")
		void shouldCountTokenOnceAcrossItsExpansions() {
			for (MergeStrategy strategy : MergeStrategy.values()) {
				final Result result = FulltextPhaseOneScorer.score(
					new int[]{3, 7, 11},
					new Expansion[][]{{expansion(1, 7, 200), expansion(0, 7, 100, 11, 90)}},
					10,
					strategy
				);
				assertArrayEquals(new int[]{7, 11}, result.primaryKeys(), strategy.name());
				final long seven = result.composites()[0];
				assertEquals(1, lane(seven, 56), "matched tokens of 7");
				assertEquals(1, lane(seven, 48), "7 matched exactly through one expansion");
				assertEquals(0xFF - 1, lane(seven, 40), "best typo of 7 is distance 0, stored one-based");
				assertEquals(200, lane(seven, 32), "impact of 7 is the maximum across expansions");
				assertEquals(1, lane(result.composites()[1], 56), "matched tokens of 11");
				assertEquals(2, result.matchedDocuments());
			}
		}

		@Test
		@DisplayName("Two tokens hitting one entity count twice")
		void shouldCountDistinctTokens() {
			for (MergeStrategy strategy : MergeStrategy.values()) {
				final Result result = FulltextPhaseOneScorer.score(
					new int[]{3, 7, 11},
					new Expansion[][]{{expansion(0, 7, 200)}, {expansion(0, 7, 10)}},
					10,
					strategy
				);
				assertArrayEquals(new int[]{7}, result.primaryKeys());
				assertEquals(2, lane(result.composites()[0], 56), strategy.name());
				assertEquals(200, lane(result.composites()[0], 32), strategy.name());
			}
		}

	}

	@Nested
	@DisplayName("Ranking")
	class Ranking {

		@Test
		@DisplayName("Lanes rank in order: matched tokens, exactness, typo distance, impact")
		void shouldRankByLanesInOrder() {
			// expected order, computed by hand:
			// 1 - both tokens, exact                      -> first
			// 2 - both tokens, but only through typos      -> second (matched tokens beat exactness)
			// 3 - one token, exact, low impact             -> third
			// 4 - one token, distance 1, high impact       -> fourth (exactness beats impact)
			// 5 - one token, distance 2, high impact       -> fifth  (smaller distance wins among typo-only)
			// 6 - nothing                                 -> absent
			final Result result = FulltextPhaseOneScorer.score(
				new int[]{1, 2, 3, 4, 5, 6},
				new Expansion[][]{
					{expansion(0, 1, 10, 3, 5), expansion(1, 2, 250, 4, 250), expansion(2, 5, 250)},
					{expansion(0, 1, 10), expansion(1, 2, 250)}
				},
				10
			);
			assertArrayEquals(new int[]{1, 2, 3, 4, 5}, result.primaryKeys());
			assertEquals(5, result.matchedDocuments());
			assertEquals(7, result.postingsWalked());
			for (int i = 1; i < result.composites().length; i++) {
				assertTrue(result.composites()[i - 1] > result.composites()[i], "composites not descending at " + i);
			}
		}

		@Test
		@DisplayName("Equal composites are ordered by ascending primary key")
		void shouldBreakTiesByPrimaryKey() {
			final Result result = FulltextPhaseOneScorer.score(
				new int[]{4, 8, 15, 16, 23, 42},
				new Expansion[][]{{expansion(0, 4, 9, 8, 9, 15, 9, 16, 9, 23, 9, 42, 9)}},
				3
			);
			assertArrayEquals(new int[]{4, 8, 15}, result.primaryKeys());
			assertEquals(6, result.matchedDocuments());
		}

		@Test
		@DisplayName("Fewer matches than requested returns only the matches; no candidates returns nothing")
		void shouldReturnOnlyMatches() {
			final Result some = FulltextPhaseOneScorer.score(
				new int[]{1, 2, 3}, new Expansion[][]{{expansion(0, 2, 7)}, {}}, 10
			);
			assertArrayEquals(new int[]{2}, some.primaryKeys());
			final Result none = FulltextPhaseOneScorer.score(new int[0], new Expansion[][]{{expansion(0, 2, 7)}}, 10);
			assertEquals(0, none.primaryKeys().length);
			assertEquals(0, none.matchedDocuments());
		}

	}

	@Nested
	@DisplayName("Merge strategies")
	class MergeStrategies {

		@Test
		@DisplayName("Linear, galloping and adaptive merges produce identical results on random queries")
		void shouldAgreeAcrossStrategies() {
			final Random random = new Random(2026);
			for (int round = 0; round < 300; round++) {
				final int[] candidates = randomSortedDistinct(random, random.nextInt(2_000), 20_000);
				final int tokenCount = 1 + random.nextInt(4);
				final Expansion[][] tokens = new Expansion[tokenCount][];
				for (int t = 0; t < tokenCount; t++) {
					final int expansionCount = random.nextInt(4);
					tokens[t] = new Expansion[expansionCount];
					for (int e = 0; e < expansionCount; e++) {
						// from a handful of postings to many times the candidate count, so both merges get chosen
						final int[] postings = randomSortedDistinct(random, random.nextInt(3) == 0 ? 5 : 4_000, 20_000);
						final byte[] impacts = new byte[postings.length];
						random.nextBytes(impacts);
						tokens[t][e] = new Expansion(postings, impacts, random.nextInt(3));
					}
				}
				final int topN = 1 + random.nextInt(50);
				final Result linear = FulltextPhaseOneScorer.score(candidates, tokens, topN, MergeStrategy.LINEAR);
				for (MergeStrategy strategy : new MergeStrategy[]{MergeStrategy.GALLOPING, MergeStrategy.ADAPTIVE}) {
					final Result other = FulltextPhaseOneScorer.score(candidates, tokens, topN, strategy);
					assertArrayEquals(linear.primaryKeys(), other.primaryKeys(), "round " + round + " " + strategy);
					assertArrayEquals(linear.composites(), other.composites(), "round " + round + " " + strategy);
					assertEquals(linear.matchedDocuments(), other.matchedDocuments());
				}
			}
		}

	}

	@Nested
	@DisplayName("Validation")
	class Validation {

		@Test
		@DisplayName("Malformed input is refused")
		void shouldRefuseMalformedInput() {
			assertThrows(GenericEvitaInternalError.class, () -> new Expansion(new int[]{1}, new byte[0], 0));
			assertThrows(GenericEvitaInternalError.class, () -> new Expansion(new int[0], new byte[0], -1));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new Expansion(new int[0], new byte[0], FulltextPhaseOneScorer.MAX_DISTANCE + 1)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> FulltextPhaseOneScorer.score(new int[]{1}, new Expansion[][]{{expansion(0, 1, 1)}}, 0)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> FulltextPhaseOneScorer.score(
					new int[]{1}, new Expansion[FulltextPhaseOneScorer.MAX_QUERY_TOKENS + 1][0], 1
				)
			);
		}

	}

	/**
	 * Draws a sorted array of distinct ints.
	 *
	 * @param random the source of randomness
	 * @param count  how many to draw, at most `bound`
	 * @param bound  exclusive upper bound of the values
	 * @return the sorted values
	 */
	@Nonnull
	private static int[] randomSortedDistinct(@Nonnull Random random, int count, int bound) {
		final TreeSet<Integer> values = new TreeSet<>();
		while (values.size() < count) {
			values.add(random.nextInt(bound));
		}
		return values.stream().mapToInt(Integer::intValue).toArray();
	}

}
