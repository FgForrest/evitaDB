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
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.bitmap.SortedArrayBitmap;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Expansion;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.MergeStrategy;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Random;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the phase-1 scorer: the counting rule, the lane composition and ordering, the top-N selection, that the
 * linear, the galloping and the skipping merge all score exactly like a naive lookup on every tier of posting list, and
 * which merge the adaptive choice picks.
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
		return new Expansion(new BaseBitmap(postings), ImpactView.of(impacts), distance);
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

		@Test
		@DisplayName("An expansion at a distance of 127 or more never overrides a closer one of the same token")
		void shouldKeepTheBestTypoLaneWhenADistantExpansionAlsoHits() {
			final Expansion exact = expansion(0, 1, 10);
			final Expansion distant = expansion(200, 1, 10);
			for (MergeStrategy strategy : MergeStrategy.values()) {
				for (Expansion[] expansions : new Expansion[][]{{exact, distant}, {distant, exact}}) {
					final Result result = FulltextPhaseOneScorer.score(
						new int[]{1}, new Expansion[][]{expansions}, 1, strategy
					);
					final long composite = result.composites()[0];
					final String message = strategy.name() + ", distance " + expansions[0].distance() + " first";
					assertEquals(1, lane(composite, 48), message);
					assertEquals(0xFF - 1, lane(composite, 40), message);
					assertEquals(1L << 56 | 1L << 48 | 0xFEL << 40 | 10L << 32, composite, message);
				}
			}
		}

		@Test
		@DisplayName("A token at a distance of 127 or more never overrides a closer token's typo lane")
		void shouldKeepTheBestTypoLaneAcrossTokens() {
			final Expansion[] exact = {expansion(0, 1, 10)};
			final Expansion[] distant = {expansion(200, 1, 10)};
			for (MergeStrategy strategy : MergeStrategy.values()) {
				for (Expansion[][] tokens : new Expansion[][][]{{exact, distant}, {distant, exact}}) {
					final Result result = FulltextPhaseOneScorer.score(new int[]{1}, tokens, 1, strategy);
					final long composite = result.composites()[0];
					final String message = strategy.name() + ", distance " + tokens[0][0].distance() + " first";
					assertEquals(2, lane(composite, 56), message);
					assertEquals(1, lane(composite, 48), message);
					assertEquals(0xFF - 1, lane(composite, 40), message);
				}
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
		@DisplayName("A candidate matching 128 or more tokens ranks above one matching fewer")
		void shouldRankByMatchedTokensBeyond127() {
			// 128 tokens hit candidate 1; only the first one hits candidate 2 - the matched-token lane of candidate 1
			// sets the composite's top bit
			final Expansion[][] tokens = new Expansion[128][];
			tokens[0] = new Expansion[]{expansion(0, 1, 10, 2, 10)};
			for (int i = 1; i < tokens.length; i++) {
				tokens[i] = new Expansion[]{expansion(0, 1, 10)};
			}
			for (MergeStrategy strategy : MergeStrategy.values()) {
				final Result top = FulltextPhaseOneScorer.score(new int[]{1, 2}, tokens, 1, strategy);
				assertArrayEquals(new int[]{1}, top.primaryKeys(), strategy.name());
				assertEquals(128, lane(top.composites()[0], 56), strategy.name());
				final Result both = FulltextPhaseOneScorer.score(new int[]{1, 2}, tokens, 2, strategy);
				assertArrayEquals(new int[]{1, 2}, both.primaryKeys(), strategy.name());
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
		@DisplayName("Every merge scores exactly like a naive lookup, on every tier of posting list")
		void shouldScoreLikeNaiveLookupWithEveryStrategy() {
			final Random random = new Random(2026);
			// several roaring containers, so the whole-list decode and the ranks cross container boundaries
			final int bound = 300_000;
			for (int round = 0; round < 120; round++) {
				final int[] candidates = randomCandidates(random, bound);
				final int tokenCount = 1 + random.nextInt(4);
				final Expansion[][] tokens = new Expansion[tokenCount][];
				for (int t = 0; t < tokenCount; t++) {
					final int expansionCount = random.nextInt(4);
					tokens[t] = new Expansion[expansionCount];
					for (int e = 0; e < expansionCount; e++) {
						final Bitmap postings = randomPostings(random, bound);
						final byte[] impacts = new byte[postings.size()];
						random.nextBytes(impacts);
						tokens[t][e] = new Expansion(postings, ImpactView.of(impacts), random.nextInt(3));
					}
				}
				final int topN = 1 + random.nextInt(50);
				final Result expected = naiveScore(candidates, tokens, topN);
				for (MergeStrategy strategy : MergeStrategy.values()) {
					final Result actual = FulltextPhaseOneScorer.score(candidates, tokens, topN, strategy);
					final String message = "round " + round + " " + strategy;
					assertArrayEquals(expected.primaryKeys(), actual.primaryKeys(), message);
					assertArrayEquals(expected.composites(), actual.composites(), message);
					assertEquals(expected.matchedDocuments(), actual.matchedDocuments(), message);
				}
			}
		}

		@Test
		@DisplayName("Every merge reads the right impact on lists around the batch size, roaring or positional")
		void shouldScoreLikeNaiveLookupAroundBatchSize() {
			final Random random = new Random(256);
			// one posting short of a full batch, exactly one batch, one posting over, and two full batches
			for (final int postingCount : new int[]{255, 256, 257, 512}) {
				final int[] postings = new int[postingCount];
				for (int i = 0; i < postingCount; i++) {
					// several roaring containers, so the whole-list decode and the ranks cross container boundaries
					postings[i] = i * 300;
				}
				// random bytes rather than a pattern: a pattern repeating every 256 postings would hide an impact read
				// one whole batch off
				final byte[] impacts = new byte[postingCount];
				random.nextBytes(impacts);
				// every other candidate is a posting, and the last one lies past the last posting, so hits and misses
				// interleave and the postings run out first
				final int[] candidates = new int[2 * postingCount];
				for (int i = 0; i < candidates.length; i++) {
					candidates[i] = i * 150;
				}
				for (final Bitmap tier : new Bitmap[]{new BaseBitmap(postings), new SortedArrayBitmap(postings)}) {
					final Expansion[][] tokens = {{new Expansion(tier, ImpactView.of(impacts), 0)}};
					final Result expected = naiveScore(candidates, tokens, postingCount);
					for (final MergeStrategy strategy : MergeStrategy.values()) {
						final Result actual = FulltextPhaseOneScorer.score(candidates, tokens, postingCount, strategy);
						final String message = postingCount + " " + tier.getClass().getSimpleName() + " " + strategy;
						assertEquals(postingCount, actual.matchedDocuments(), message);
						assertArrayEquals(expected.primaryKeys(), actual.primaryKeys(), message);
						assertArrayEquals(expected.composites(), actual.composites(), message);
					}
				}
			}
		}

		@Test
		@DisplayName("Every merge reads a posting's impact in the posting list's unsigned record order")
		void shouldReadImpactsInUnsignedRecordOrder() {
			// a roaring list orders -1 after 1 (unsigned) and its impacts follow that order; a signed decode would put
			// -1 first and hand candidate 1 the impact of -1
			final Expansion[][] tokens = {
				{new Expansion(new BaseBitmap(1, -1), ImpactView.of(new byte[]{10, (byte) 200}), 0)}
			};
			for (final MergeStrategy strategy : MergeStrategy.values()) {
				final Result result = FulltextPhaseOneScorer.score(new int[]{1}, tokens, 1, strategy);
				assertArrayEquals(new int[]{1}, result.primaryKeys(), strategy.name());
				assertEquals(10, lane(result.composites()[0], 32), strategy.name());
			}
		}

		@Test
		@DisplayName("Every posting counts as walked, whichever merge runs and however early the candidates run out")
		void shouldCountEveryPostingAsWalkedWhicheverMergeRuns() {
			final int[] postings = new int[1_000];
			for (int i = 0; i < postings.length; i++) {
				postings[i] = i * 3;
			}
			final Expansion[][] tokens = {
				{
					new Expansion(new BaseBitmap(postings), ImpactView.of(new byte[postings.length]), 0),
					new Expansion(new SingleRecordBitmap(7), ImpactView.of(new byte[1]), 0)
				},
				{new Expansion(new SortedArrayBitmap(2, 4, 6), ImpactView.of(new byte[3]), 1)}
			};
			for (final MergeStrategy strategy : MergeStrategy.values()) {
				// the candidates run out at the third posting of the long list: 0 hits it, 4 misses it below 6
				final Result early = FulltextPhaseOneScorer.score(new int[]{0, 4}, tokens, 10, strategy);
				assertEquals(1_004L, early.postingsWalked(), strategy.name());
				assertEquals(2, early.matchedDocuments(), strategy.name());
				final Result none = FulltextPhaseOneScorer.score(new int[0], tokens, 10, strategy);
				assertEquals(1_004L, none.postingsWalked(), strategy.name());
				assertEquals(0, none.matchedDocuments(), strategy.name());
			}
		}

		@ParameterizedTest(name = "{0} candidates, {1} postings -> {2}")
		@CsvSource({
			// comparable lengths walk linearly - a posting list just shorter than the candidates must not gallop
			"236000, 200000, LINEAR",
			"1000, 1000, LINEAR",
			"1000, 1001, LINEAR",
			// a gap of 1.9 rounds up to two probes: 4,000 against a linear 2,900, where one probe would gallop at 2,000
			"1900, 1000, LINEAR",
			"2000, 1000, LINEAR",
			// galloping meets the linear cost exactly, 8,000 each, and a tie stays linear
			"7000, 1000, LINEAR",
			"1000, 16000, LINEAR",
			"1, 10, LINEAR",
			// a short posting list against many candidates gallops over the candidates
			"8000, 1000, GALLOPING",
			"1000000, 10, GALLOPING",
			"10, 1, GALLOPING",
			// a few candidates against a long posting list skip through the postings
			"401, 63000, SKIPPING",
			"1000, 24000, SKIPPING",
			"0, 5, SKIPPING"
		})
		@DisplayName("The adaptive choice takes the cheapest merge for the two lengths")
		void shouldChooseCheapestMergeForTheLengths(int candidateCount, int postingCount, MergeStrategy expected) {
			assertEquals(
				expected, FulltextPhaseOneScorer.chooseMerge(MergeStrategy.ADAPTIVE, candidateCount, postingCount)
			);
		}

		@Test
		@DisplayName("A forced strategy is taken as it is")
		void shouldTakeAForcedStrategyAsItIs() {
			final MergeStrategy[] forced = {MergeStrategy.LINEAR, MergeStrategy.GALLOPING, MergeStrategy.SKIPPING};
			for (final MergeStrategy strategy : forced) {
				assertEquals(strategy, FulltextPhaseOneScorer.chooseMerge(strategy, 401, 63_000));
			}
		}

	}

	@Nested
	@DisplayName("Validation")
	class Validation {

		@Test
		@DisplayName("Malformed input is refused")
		void shouldRefuseMalformedInput() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new Expansion(new BaseBitmap(1), ImpactView.of(new byte[0]), 0)
			);
			assertThrows(
				GenericEvitaInternalError.class, () -> new Expansion(new BaseBitmap(), ImpactView.of(new byte[0]), -1)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new Expansion(
					new BaseBitmap(), ImpactView.of(new byte[0]), FulltextPhaseOneScorer.MAX_DISTANCE + 1
				)
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
	 * Scores the candidates by looking every candidate up in every posting list - the definition the merges must
	 * reproduce, computed without any of them.
	 *
	 * @param candidates the candidates, ascending
	 * @param tokens     the query tokens
	 * @param topN       how many entities to return
	 * @return the expected result
	 */
	@Nonnull
	private static Result naiveScore(@Nonnull int[] candidates, @Nonnull Expansion[][] tokens, int topN) {
		final int[][][] postings = new int[tokens.length][][];
		final byte[][][] impacts = new byte[tokens.length][][];
		for (int t = 0; t < tokens.length; t++) {
			postings[t] = new int[tokens[t].length][];
			impacts[t] = new byte[tokens[t].length][];
			for (int e = 0; e < tokens[t].length; e++) {
				postings[t][e] = tokens[t][e].postings().getArray();
				impacts[t][e] = tokens[t][e].impacts().toArray();
			}
		}
		final long[] composites = new long[candidates.length];
		int matchedDocuments = 0;
		for (int c = 0; c < candidates.length; c++) {
			int matched = 0;
			int exactness = 0;
			int bestTypo = 0;
			int maxImpact = 0;
			for (int t = 0; t < tokens.length; t++) {
				int tokenTypo = 0;
				int tokenImpact = 0;
				for (int e = 0; e < tokens[t].length; e++) {
					final int position = Arrays.binarySearch(postings[t][e], candidates[c]);
					if (position >= 0) {
						final int typo = tokens[t][e].distance() + 1;
						tokenTypo = tokenTypo == 0 ? typo : Math.min(tokenTypo, typo);
						tokenImpact = Math.max(tokenImpact, Byte.toUnsignedInt(impacts[t][e][position]));
					}
				}
				if (tokenTypo != 0) {
					matched++;
					maxImpact = Math.max(maxImpact, tokenImpact);
					bestTypo = bestTypo == 0 ? tokenTypo : Math.min(bestTypo, tokenTypo);
					if (tokenTypo == 1) {
						exactness = 1;
					}
				}
			}
			if (matched > 0) {
				matchedDocuments++;
				composites[c] = ((long) matched << 56) | ((long) exactness << 48) | ((long) (0xFF - bestTypo) << 40)
					| ((long) maxImpact << 32);
			}
		}
		// only a matched candidate can be selected, so only the matched ones are ordered
		final Integer[] order = new Integer[matchedDocuments];
		int matchedIndex = 0;
		for (int c = 0; c < candidates.length; c++) {
			if (composites[c] != 0L) {
				order[matchedIndex++] = c;
			}
		}
		Arrays.sort(order, (a, b) -> {
			final int byComposite = Long.compareUnsigned(composites[b], composites[a]);
			return byComposite != 0 ? byComposite : Integer.compare(candidates[a], candidates[b]);
		});
		final int selected = Math.min(topN, matchedDocuments);
		final int[] primaryKeys = new int[selected];
		final long[] selectedComposites = new long[selected];
		for (int i = 0; i < selected; i++) {
			primaryKeys[i] = candidates[order[i]];
			selectedComposites[i] = composites[order[i]];
		}
		return new Result(primaryKeys, selectedComposites, matchedDocuments, 0L);
	}

	/**
	 * Draws candidates from a handful to a hundred thousand, so that against the posting lists below every merge
	 * gets chosen.
	 *
	 * @param random the source of randomness
	 * @param bound  exclusive upper bound of the values
	 * @return the candidates, ascending
	 */
	@Nonnull
	private static int[] randomCandidates(@Nonnull Random random, int bound) {
		return switch (random.nextInt(4)) {
			case 0 -> randomSorted(random, 1 + random.nextInt(50), bound);
			case 1 -> randomSorted(random, 500, bound);
			case 2 -> randomSorted(random, 5_000, bound);
			default -> randomSorted(random, 100_000, bound);
		};
	}

	/**
	 * Draws a posting list of one of the tiers a dictionary bucket takes: a single record, a sorted array of a few,
	 * or a roaring bitmap whose containers are arrays (sparse), bitmaps (dense) or runs.
	 *
	 * @param random the source of randomness
	 * @param bound  exclusive upper bound of the values
	 * @return the posting list, not empty
	 */
	@Nonnull
	private static Bitmap randomPostings(@Nonnull Random random, int bound) {
		return switch (random.nextInt(5)) {
			case 0 -> new SingleRecordBitmap(random.nextInt(bound));
			case 1 -> new SortedArrayBitmap(randomSorted(random, 2 + random.nextInt(127), bound));
			case 2 -> new BaseBitmap(randomSorted(random, 4_000, bound));
			case 3 -> new BaseBitmap(randomSorted(random, 40_000, bound));
			default -> {
				final PersistentRoaringBitmap runs = new PersistentRoaringBitmap();
				for (int i = 0; i < 3; i++) {
					final long start = random.nextInt(bound - 20_000);
					runs.add(start, start + 1_000 + random.nextInt(19_000));
				}
				runs.runOptimize();
				yield new BaseBitmap(runs);
			}
		};
	}

	/**
	 * Draws about `count` distinct values below `bound`, ascending, with exponentially distributed gaps; at least one
	 * value is always drawn.
	 *
	 * @param random the source of randomness
	 * @param count  how many values to draw on average, at most `bound`
	 * @param bound  exclusive upper bound of the values
	 * @return the sorted values
	 */
	@Nonnull
	private static int[] randomSorted(@Nonnull Random random, int count, int bound) {
		final double meanExtraGap = Math.max(0.0, (double) bound / count - 1.0);
		int[] values = new int[count + count / 2 + 8];
		int size = 0;
		long value = (long) (random.nextDouble() * (meanExtraGap + 1.0));
		while (value < bound) {
			if (size == values.length) {
				values = Arrays.copyOf(values, size * 2);
			}
			values[size++] = (int) value;
			value += 1 + (long) (-Math.log(1.0 - random.nextDouble()) * meanExtraGap);
		}
		if (size == 0) {
			values[size++] = random.nextInt(bound);
		}
		return Arrays.copyOf(values, size);
	}

}
