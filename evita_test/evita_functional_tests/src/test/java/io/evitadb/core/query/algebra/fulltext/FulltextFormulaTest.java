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

package io.evitadb.core.query.algebra.fulltext;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula.ExpandedTerm;
import io.evitadb.core.query.algebra.prefetch.EntityFilteringFormula;
import io.evitadb.core.query.algebra.prefetch.EntityToBitmapFilter;
import io.evitadb.core.query.algebra.prefetch.SelectionFormula;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Expansion;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.analysis.AnalyzedTerm;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzerRegistry;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static io.evitadb.index.fulltext.FulltextFieldKey.attribute;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FulltextFormula} - its strict match set, the scores it hands out through {@link FulltextScoreAccessor},
 * its hash - and the delegation of that accessor through the prefetch wrappers {@link SelectionFormula} and
 * {@link EntityFilteringFormula}, without which relevance would silently stop ordering whenever the planner chose
 * prefetch.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext formula")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
class FulltextFormulaTest {

	/**
	 * Words the randomized test builds values from - content words only, so each analyzes to at least one term.
	 */
	private static final String[] VOCABULARY = {
		"Praha", "Brno", "Ostrava", "kolo", "kola", "horské", "horský", "elektrické", "město", "hrad", "hradu",
		"řeka", "most", "mostu", "žlutý", "kůň"
	};

	/**
	 * Registry providing the real Czech index-slot analyzer.
	 */
	private static FulltextAnalyzerRegistry registry;

	/**
	 * The Czech index-slot analyzer the randomized test indexes with.
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

	/**
	 * Creates an expanded term over explicit postings and impacts.
	 *
	 * @param term     the term
	 * @param distance the edit distance
	 * @param postings the postings and, pairwise, their impacts: `pk1, impact1, pk2, impact2, ...`
	 * @return the term in field 0
	 */
	@Nonnull
	private static ExpandedTerm term(@Nonnull String term, int distance, @Nonnull int... postings) {
		final int[] primaryKeys = new int[postings.length / 2];
		final byte[] impacts = new byte[postings.length / 2];
		for (int i = 0; i < primaryKeys.length; i++) {
			primaryKeys[i] = postings[i * 2];
			impacts[i] = (byte) postings[i * 2 + 1];
		}
		return new ExpandedTerm(
			0, term, primaryKeys.length == 0 ? EmptyBitmap.INSTANCE : new ArrayBitmap(primaryKeys),
			ImpactView.of(impacts), distance
		);
	}

	/**
	 * Creates the query of the passed tokens.
	 *
	 * @param tokens the tokens, each as its terms
	 * @return the formula
	 */
	@Nonnull
	private static FulltextFormula query(@Nonnull ExpandedTerm[]... tokens) {
		return new FulltextFormula(tokens);
	}

	/**
	 * Groups terms into one token.
	 *
	 * @param terms the terms
	 * @return the token
	 */
	@Nonnull
	private static ExpandedTerm[] token(@Nonnull ExpandedTerm... terms) {
		return terms;
	}

	/**
	 * Creates an expanded term over a posting list whose reads are counted, with zero impacts.
	 *
	 * @param term     the term
	 * @param postings the posting list
	 * @return the term in field 0, exact
	 */
	@Nonnull
	private static ExpandedTerm countedTerm(@Nonnull String term, @Nonnull ReadCountingBitmap postings) {
		return new ExpandedTerm(0, term, postings, ImpactView.of(new byte[postings.size()]), 0);
	}

	/**
	 * A posting list that counts how often the formula reads its roaring bitmap - the one access through which both
	 * matching routes reach a term's postings, so the counts tell which route a token took. Its own operations use
	 * the bitmap field directly and are not counted.
	 */
	private static final class ReadCountingBitmap extends BaseBitmap {
		/**
		 * How many times the roaring bitmap was read.
		 */
		private int reads;

		/**
		 * Creates the posting list of the passed primary keys.
		 *
		 * @param primaryKeys the primary keys
		 */
		ReadCountingBitmap(@Nonnull int... primaryKeys) {
			super(primaryKeys);
		}

		@Nonnull
		@Override
		public PersistentRoaringBitmap getRoaringBitmap() {
			this.reads++;
			return super.getRoaringBitmap();
		}
	}

	/**
	 * Converts tokens to the scorer's expansions, independently of the formula's own conversion.
	 *
	 * @param formula the formula whose tokens to convert
	 * @return the expansions
	 */
	@Nonnull
	private static Expansion[][] expansionsOf(@Nonnull FulltextFormula formula) {
		final ExpandedTerm[][] tokens = formula.getTokens();
		final Expansion[][] result = new Expansion[tokens.length][];
		for (int i = 0; i < tokens.length; i++) {
			result[i] = new Expansion[tokens[i].length];
			for (int j = 0; j < tokens[i].length; j++) {
				final ExpandedTerm term = tokens[i][j];
				// fresh copies of both, so the formula's own hand-over of the dictionary's views is what gets compared
				result[i][j] = new Expansion(
					new BaseBitmap(term.postings().getArray()), ImpactView.of(term.impacts().toArray()), term.distance()
				);
			}
		}
		return result;
	}

	/**
	 * Asserts two scoring results are identical.
	 *
	 * @param expected the expected result
	 * @param actual   the actual result
	 */
	private static void assertSameScores(@Nonnull Result expected, @Nonnull Result actual) {
		assertArrayEquals(expected.primaryKeys(), actual.primaryKeys());
		assertArrayEquals(expected.composites(), actual.composites());
		assertEquals(expected.matchedDocuments(), actual.matchedDocuments());
	}

	/**
	 * A sample query with two tokens, the first in two terms.
	 *
	 * @return the formula
	 */
	@Nonnull
	private static FulltextFormula sampleQuery() {
		return query(
			token(term("kol", 0, 1, 10, 2, 20, 5, 50), term("kolo", 1, 3, 30)),
			token(term("hrad", 0, 2, 200, 3, 100, 7, 70))
		);
	}

	@Nested
	@DisplayName("Match set")
	class MatchSet {

		@Test
		@DisplayName("A single token matches the union of its terms")
		void shouldMatchTheUnionOfOneTokensTerms() {
			final FulltextFormula formula = query(token(term("a", 0, 1, 1, 4, 1), term("b", 1, 2, 1, 4, 1)));

			assertArrayEquals(new int[]{1, 2, 4}, formula.compute().getArray());
		}

		@Test
		@DisplayName("Several tokens match the intersection of their unions")
		void shouldMatchTheIntersectionOfTokenUnions() {
			assertArrayEquals(new int[]{2, 3}, sampleQuery().compute().getArray());
		}

		@Test
		@DisplayName("Random queries of many terms per token match the intersection of the token unions")
		void shouldMatchTheIntersectionOfTokenUnionsOnRandomQueries() {
			final Random random = new Random(31);
			final int bound = 100_000;
			for (int round = 0; round < 60; round++) {
				final int tokenCount = 1 + random.nextInt(4);
				final ExpandedTerm[][] tokens = new ExpandedTerm[tokenCount][];
				BitSet expected = null;
				for (int t = 0; t < tokenCount; t++) {
					final int termCount = 1 + random.nextInt(30);
					tokens[t] = new ExpandedTerm[termCount];
					final BitSet union = new BitSet(bound);
					int[] first = null;
					for (int e = 0; e < termCount; e++) {
						// from a few postings to a quarter of the ids; now and then a repeat of the token's first
						// term, so a narrowed token is covered before its last term is reached
						final int count = random.nextInt(3) == 0 ? bound / 4 : 1 + random.nextInt(2_000);
						final int[] postings = first != null && random.nextInt(4) == 0
							? first
							: randomPostings(random, count, bound);
						if (first == null) {
							first = postings;
						}
						for (int pk : postings) {
							union.set(pk);
						}
						tokens[t][e] = new ExpandedTerm(
							0, "t" + t + "_" + e, new BaseBitmap(postings), ImpactView.of(new byte[postings.length]), 0
						);
					}
					if (expected == null) {
						expected = union;
					} else {
						expected.and(union);
					}
				}
				assertArrayEquals(
					expected.stream().toArray(), query(tokens).compute().getArray(), "round " + round
				);
			}
		}

		@Test
		@DisplayName("A token without terms matches nothing, and so does the query")
		void shouldMatchNothingWhenATokenHasNoTerm() {
			final FulltextFormula formula = query(token(term("a", 0, 1, 1)), token());

			assertSame(EmptyBitmap.INSTANCE, formula.compute());
		}

		@Test
		@DisplayName("A token whose terms have no postings matches nothing")
		void shouldMatchNothingWhenATokensTermsAreEmpty() {
			final FulltextFormula formula = query(token(term("a", 0, 1, 1)), token(term("b", 0)));

			assertSame(EmptyBitmap.INSTANCE, formula.compute());
		}

		@Test
		@DisplayName("Disjoint tokens match nothing")
		void shouldMatchNothingWhenTokensAreDisjoint() {
			final FulltextFormula formula = query(token(term("a", 0, 1, 1)), token(term("b", 0, 2, 1)));

			assertSame(EmptyBitmap.INSTANCE, formula.compute());
		}

		@Test
		@DisplayName("A token of several terms, none of which meets the narrower tokens, matches nothing")
		void shouldMatchNothingWhenNoTermOfANarrowedTokenHits() {
			// the first token is the narrower one and matches 3 entities; the second spreads 12 postings over 3 terms,
			// more than the 3 x 3 values narrowing walks, so it narrows term by term, and every intersection is empty
			final FulltextFormula formula = query(
				token(term("a", 0, 1, 1, 2, 1, 3, 1)),
				token(
					term("b", 0, 10, 1, 11, 1, 12, 1, 13, 1, 14, 1),
					term("c", 0, 20, 1, 21, 1, 22, 1, 23, 1, 24, 1),
					term("d", 0, 30, 1, 31, 1)
				)
			);
			// both routes match the same entities, so only the rule tells that this fixture takes the narrow route
			assertTrue(FulltextFormula.narrowsCheaper(3, 3, 12));

			assertSame(EmptyBitmap.INSTANCE, formula.compute());
		}

		@Test
		@DisplayName("A token of several terms whose first term misses still matches through its later terms")
		void shouldMatchThroughLaterTermsWhenTheFirstTermOfANarrowedTokenMisses() {
			// the first token is the narrower one and matches 2 entities; the second spreads 7 postings over 3 terms,
			// more than the 3 x 2 values narrowing walks, so it narrows term by term - of its terms the first meets
			// none of the entities, the second meets one and the third the other
			final FulltextFormula formula = query(
				token(term("a", 0, 5, 1, 6, 1)),
				token(term("b", 0, 100, 1, 101, 1, 102, 1), term("c", 0, 5, 1), term("d", 0, 6, 1, 200, 1, 201, 1))
			);
			// both routes match the same entities, so only the rule tells that this fixture takes the narrow route
			assertTrue(FulltextFormula.narrowsCheaper(3, 2, 7));

			assertArrayEquals(new int[]{5, 6}, formula.compute().getArray());
		}

		@Test
		@DisplayName("A wide narrower token met by a token of many terms matches the intersection through its union")
		void shouldMatchTheIntersectionThroughTheUnionWhenTheMatchingEntitiesAreMany() {
			// the first token matches the 1,000 even keys below 2,000; the second spreads 1,200 postings over 20 terms,
			// term j holding every twentieth key from j: narrowing would walk the 1,000 entities 20 times, so the
			// second token's union - every key below 1,200 - is built and intersected once
			final int[] evenKeys = new int[1_000];
			for (int i = 0; i < evenKeys.length; i++) {
				evenKeys[i] = i * 2;
			}
			final ExpandedTerm[] secondToken = new ExpandedTerm[20];
			for (int j = 0; j < secondToken.length; j++) {
				final int[] postings = new int[60];
				for (int k = 0; k < postings.length; k++) {
					postings[k] = j + k * 20;
				}
				secondToken[j] = new ExpandedTerm(
					0, "t" + j, new BaseBitmap(postings), ImpactView.of(new byte[postings.length]), 0
				);
			}
			final ExpandedTerm firstToken = new ExpandedTerm(
				0, "even", new BaseBitmap(evenKeys), ImpactView.of(new byte[evenKeys.length]), 0
			);

			final int[] expected = new int[600];
			for (int i = 0; i < expected.length; i++) {
				expected[i] = i * 2;
			}
			assertArrayEquals(expected, query(token(firstToken), secondToken).compute().getArray());
		}

		@Test
		@DisplayName("A token narrows term by term only while that walks fewer values than building its union")
		void shouldNarrowTermByTermOnlyWhileItWalksFewerValuesThanTheUnion() {
			// few matching entities against long posting lists: narrowing walks 3 x 10 values, the union 3,000
			assertTrue(FulltextFormula.narrowsCheaper(3, 10, 3_000));
			// one posting past a tie narrows: narrowing walks 2 x 500 values, the union 1,001
			assertTrue(FulltextFormula.narrowsCheaper(2, 500, 1_001));
			// many matching entities against a token of many terms: narrowing walks 20 x 1,000 values, the union 1,200
			assertFalse(FulltextFormula.narrowsCheaper(20, 1_000, 1_200));
			// a tie goes to the union, which computes one intersection instead of one per term
			assertFalse(FulltextFormula.narrowsCheaper(2, 500, 1_000));
		}

		@Test
		@DisplayName("A token takes its union once the matching entities exceed the narrowing limit")
		void shouldTakeTheUnionOnceTheMatchingEntitiesExceedTheNarrowingLimit() {
			final long limit = FulltextFormula.NARROWING_MATCHING_LIMIT;
			// at the limit the walked values still decide: narrowing walks 2 x limit values, the union 100 x limit
			assertTrue(FulltextFormula.narrowsCheaper(2, limit, 100 * limit));
			// one entity past it the union is built, although narrowing would still walk fewer values
			assertFalse(FulltextFormula.narrowsCheaper(2, limit + 1, 100 * limit));
		}

		@Test
		@DisplayName("A token met by more matching entities than the limit reads every term through its union")
		void shouldReadEveryTermThroughTheUnionPastTheNarrowingLimit() {
			// the narrower token matches one entity more than the limit; the wider one spreads its postings over two
			// terms, more than the 2 x matching values narrowing would walk, and its first term alone covers every
			// matching entity - narrowing would stop reading after it, the union reads both terms
			final int matchingCount = (int) FulltextFormula.NARROWING_MATCHING_LIMIT + 1;
			final int[] matching = IntStream.range(0, matchingCount).toArray();
			final ReadCountingBitmap first = new ReadCountingBitmap(
				IntStream.range(0, 2 * matchingCount + 1).toArray()
			);
			final ReadCountingBitmap second = new ReadCountingBitmap(5 * matchingCount);
			final FulltextFormula formula = query(
				token(countedTerm("a", new ReadCountingBitmap(matching))),
				token(countedTerm("b", first), countedTerm("c", second))
			);

			assertArrayEquals(matching, formula.compute().getArray());
			assertEquals(1, first.reads, "the first term");
			assertEquals(1, second.reads, "the second term");
		}

		@Test
		@DisplayName("A token narrowing term by term reads no further term once the matching entities are covered")
		void shouldStopReadingTermsOnceNarrowingCoversTheMatchingEntities() {
			// the narrower token matches 3 entities; the wider one spreads 21 postings over 3 terms, more than the
			// 3 x 3 values narrowing walks, so it narrows term by term - and its first term already covers the 3
			// entities; the wider token is passed first, so the formula has to order the tokens itself
			final ReadCountingBitmap first = new ReadCountingBitmap(1, 2, 3, 10, 11, 12, 13);
			final ReadCountingBitmap second = new ReadCountingBitmap(20, 21, 22, 23, 24, 25, 26);
			final ReadCountingBitmap third = new ReadCountingBitmap(30, 31, 32, 33, 34, 35, 36);
			final FulltextFormula formula = query(
				token(countedTerm("b", first), countedTerm("c", second), countedTerm("d", third)),
				token(term("a", 0, 1, 1, 2, 1, 3, 1))
			);

			assertArrayEquals(new int[]{1, 2, 3}, formula.compute().getArray());
			assertEquals(1, first.reads, "the first term");
			assertEquals(0, second.reads, "the second term");
			assertEquals(0, third.reads, "the third term");
		}

		@Test
		@DisplayName("A token taking its union reads every term, even when its first one covers the matching entities")
		void shouldReadEveryTermWhenTheTokenTakesItsUnion() {
			// the narrower token matches 10 entities; the wider one spreads 20 postings over 2 terms - exactly the
			// 2 x 10 values narrowing would walk, and a tie goes to the union, which reads both terms although the
			// first one alone covers the 10 entities
			final ReadCountingBitmap first = new ReadCountingBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
			final ReadCountingBitmap second = new ReadCountingBitmap(100, 101, 102, 103, 104, 105, 106, 107, 108, 109);
			final FulltextFormula formula = query(
				token(countedTerm("a", new ReadCountingBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))),
				token(countedTerm("b", first), countedTerm("c", second))
			);

			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, formula.compute().getArray());
			assertEquals(1, first.reads, "the first term");
			assertEquals(1, second.reads, "the second term");
		}

		@Test
		@DisplayName("Each token weighs its route against the entities still matching after the tokens before it")
		void shouldWeighEachTokensRouteAgainstTheEntitiesStillMatching() {
			// the narrowest token matches 10 entities and the next one, a single term, cuts them down to 2; the widest
			// spreads 20 postings over 2 terms - against the 10 entities first matched it would tie and take its
			// union, against the 2 still matching it narrows term by term, and its first term covers both
			final ReadCountingBitmap first = new ReadCountingBitmap(1, 2, 200, 201, 202, 203, 204, 205, 206, 207);
			final ReadCountingBitmap second = new ReadCountingBitmap(300, 301, 302, 303, 304, 305, 306, 307, 308, 309);
			final FulltextFormula formula = query(
				token(countedTerm("a", new ReadCountingBitmap(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))),
				token(countedTerm("b", new ReadCountingBitmap(1, 2, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59))),
				token(countedTerm("c", first), countedTerm("d", second))
			);

			assertArrayEquals(new int[]{1, 2}, formula.compute().getArray());
			assertEquals(1, first.reads, "the first term");
			assertEquals(0, second.reads, "the second term");
		}

		@Test
		@DisplayName("Matching term by term leaves every term's posting list as it was")
		void shouldLeaveEveryTermsPostingsUnchangedWhenNarrowingTermByTerm() {
			// the wider token's 210,003 postings outnumber the 3 x 10,000 values narrowing walks: its terms are
			// intersected with the matching entities and folded together, the first intersection - a subset of its own
			// term, an array chunk - taking in a bitmap chunk and a run chunk
			assertMatchingLeavesPostingsUnchanged(400_000, true);
		}

		@Test
		@DisplayName("Matching through the token's union leaves every term's posting list as it was")
		void shouldLeaveEveryTermsPostingsUnchangedWhenIntersectingTheUnion() {
			// the wider token's 20,003 postings are fewer than the 3 x 10,000 values narrowing would walk: its union is
			// built and intersected with the matching entities once
			assertMatchingLeavesPostingsUnchanged(20_000, false);
		}

		/**
		 * Matches a narrower token of one posting list, keys 0 to 9,999 as a run, against a wider token of three
		 * terms - three keys in an array chunk, every second key below `everySecondBound` and the run of keys 5,000 to
		 * 14,999 - and checks the route the fixture takes, the match and that no term's posting list changed. The
		 * narrower token's posting list serves as the matching entities as it is; at 10,000 entities it stays within
		 * {@link FulltextFormula#NARROWING_MATCHING_LIMIT}, so the posting counts alone decide the route.
		 *
		 * @param everySecondBound the exclusive bound of the every-second-key term, which decides the route the wider
		 *                         token takes
		 * @param narrows          whether the fixture is meant to narrow term by term rather than build the union
		 */
		private static void assertMatchingLeavesPostingsUnchanged(int everySecondBound, boolean narrows) {
			final PersistentRoaringBitmap firstTenThousand = new PersistentRoaringBitmap();
			firstTenThousand.add(0L, 10_000L);
			firstTenThousand.runOptimize();
			final PersistentRoaringBitmap everySecond = new PersistentRoaringBitmap();
			for (int primaryKey = 0; primaryKey < everySecondBound; primaryKey += 2) {
				everySecond.add(primaryKey);
			}
			final PersistentRoaringBitmap fiveToFifteenThousand = new PersistentRoaringBitmap();
			fiveToFifteenThousand.add(5_000L, 15_000L);
			fiveToFifteenThousand.runOptimize();
			final Bitmap[] postings = {
				new BaseBitmap(firstTenThousand), new BaseBitmap(1, 3, 5), new BaseBitmap(everySecond),
				new BaseBitmap(fiveToFifteenThousand)
			};
			final int[][] before = new int[postings.length][];
			final ExpandedTerm[] terms = new ExpandedTerm[postings.length];
			for (int i = 0; i < postings.length; i++) {
				before[i] = postings[i].getArray();
				terms[i] = new ExpandedTerm(0, "t" + i, postings[i], ImpactView.of(new byte[postings[i].size()]), 0);
			}
			final long widerPostings = (long) postings[1].size() + postings[2].size() + postings[3].size();
			assertEquals(
				narrows, FulltextFormula.narrowsCheaper(3, postings[0].size(), widerPostings),
				"the route the fixture is meant to take"
			);

			final Bitmap matched = query(token(terms[0]), token(terms[1], terms[2], terms[3])).compute();

			// 1, 3 and 5, then the 5,000 even keys below 10,000, then the 2,500 odd keys from 5,000 to 9,999
			assertEquals(7_503, matched.size());
			for (int i = 0; i < postings.length; i++) {
				assertArrayEquals(before[i], postings[i].getArray(), "the posting list of term " + i);
			}
		}

		@Test
		@DisplayName("The estimated cardinality is the narrowest token's posting count")
		void shouldEstimateCardinalityByTheNarrowestToken() {
			// token 1 holds 4 postings over two terms, token 2 holds 3
			assertEquals(3, sampleQuery().getEstimatedCardinality());
		}

		@Test
		@DisplayName("A token without terms makes the estimated cardinality zero")
		void shouldEstimateZeroCardinalityWhenATokenHasNoTerm() {
			assertEquals(0, query(token(term("a", 0, 1, 1)), token()).getEstimatedCardinality());
		}

		@Test
		@DisplayName("The estimated cost counts every posting of every term once")
		void shouldEstimateTheCostAsEveryPostingRead() {
			// token 1 holds 3 + 1 postings, token 2 holds 3
			assertEquals(7L, sampleQuery().getEstimatedCost());
		}

	}

	@Nested
	@DisplayName("Scores")
	class Scores {

		@Test
		@DisplayName("Scores are the phase-one scorer's over the same terms and impacts")
		void shouldScoreLikeTheScorer() {
			final FulltextFormula formula = sampleQuery();
			final int[] candidates = {1, 2, 3, 5, 7, 9};

			assertSameScores(
				FulltextPhaseOneScorer.score(candidates, expansionsOf(formula), 4),
				formula.getFulltextScores(candidates, 4)
			);
		}

		@Test
		@DisplayName("A candidate matching both tokens outranks one matching a single token")
		void shouldRankFullMatchesFirst() {
			final Result result = sampleQuery().getFulltextScores(new int[]{1, 2, 3, 5, 7}, 5);

			// 2 and 3 match both tokens and both have an exact match; 2 carries the larger impact (200 against 100)
			assertArrayEquals(new int[]{2, 3}, Arrays.copyOf(result.primaryKeys(), 2));
		}

		@Test
		@DisplayName("A match through a typo ranks below an exact match even with a larger impact")
		void shouldRankTypoMatchesBelowExactOnes() {
			final FulltextFormula formula = query(token(term("kol", 0, 1, 10), term("kolo", 1, 2, 200)));

			// the edit distance must reach the scorer: without it both are exact and 2's impact would win
			assertArrayEquals(new int[]{1, 2}, formula.getFulltextScores(new int[]{1, 2}, 2).primaryKeys());
		}

		@Test
		@DisplayName("Scores are computed for the candidates passed, not for the formula's own result")
		void shouldScoreOnlyThePassedCandidates() {
			final Result result = sampleQuery().getFulltextScores(new int[]{5, 7}, 10);

			// each matches one token exactly, so the impact decides: 70 for 7 against 50 for 5
			assertArrayEquals(new int[]{7, 5}, result.primaryKeys());
			assertEquals(2, result.matchedDocuments());
		}

		@Test
		@DisplayName("Repeated scoring requests give the same answer")
		void shouldScoreRepeatably() {
			final FulltextFormula formula = sampleQuery();
			final int[] candidates = {1, 2, 3, 5, 7};

			assertSameScores(formula.getFulltextScores(candidates, 3), formula.getFulltextScores(candidates, 3));
		}

		@Test
		@DisplayName("The formula always provides scores")
		void shouldAlwaysProvideScores() {
			assertTrue(sampleQuery().providesFulltextScores());
		}

	}

	@Nested
	@DisplayName("Formula contract")
	class Contract {

		@Test
		@DisplayName("The formula is non-cacheable, so no flattened ancestor can drop its scores")
		void shouldBeNonCacheable() {
			assertInstanceOf(NonCacheableFormula.class, sampleQuery());
		}

		@Test
		@DisplayName("The hash does not depend on the order of tokens or of a token's terms")
		void shouldHashIndependentlyOfOrder() {
			final ExpandedTerm a = term("a", 0, 1, 1, 2, 1);
			final ExpandedTerm b = term("b", 1, 3, 1);
			final ExpandedTerm c = term("c", 0, 2, 1, 3, 1);

			assertEquals(
				query(token(a, b), token(c)).getHash(),
				query(token(c), token(b, a)).getHash()
			);
		}

		@Test
		@DisplayName("The hash changes with the postings and with the edit distance")
		void shouldHashPostingsAndDistance() {
			final long base = query(token(term("a", 0, 1, 1, 2, 1))).getHash();

			assertNotEquals(base, query(token(term("a", 0, 1, 1, 3, 1))).getHash());
			assertNotEquals(base, query(token(term("a", 1, 1, 1, 2, 1))).getHash());
		}

		@Test
		@DisplayName("Moving a term to another token changes the hash")
		void shouldHashTheTokenGrouping() {
			final ExpandedTerm a = term("a", 0, 1, 1);
			final ExpandedTerm b = term("b", 0, 2, 1);

			assertNotEquals(query(token(a, b)).getHash(), query(token(a), token(b)).getHash());
		}

		@Test
		@DisplayName("A query without tokens is refused")
		void shouldRefuseAQueryWithoutTokens() {
			assertThrows(GenericEvitaInternalError.class, () -> new FulltextFormula(new ExpandedTerm[0][]));
		}

		@Test
		@DisplayName("More tokens than the scorer can count are refused")
		void shouldRefuseTooManyTokens() {
			final ExpandedTerm[][] tokens = new ExpandedTerm[FulltextPhaseOneScorer.MAX_QUERY_TOKENS + 1][];
			for (int i = 0; i < tokens.length; i++) {
				tokens[i] = token(term("t" + i, 0, 1, 1));
			}
			assertThrows(GenericEvitaInternalError.class, () -> new FulltextFormula(tokens));
		}

		@Test
		@DisplayName("The most tokens and the largest edit distance the scorer can count are accepted")
		void shouldAcceptTheLargestTokenCountAndDistance() {
			final ExpandedTerm[][] tokens = new ExpandedTerm[FulltextPhaseOneScorer.MAX_QUERY_TOKENS][];
			tokens[0] = token(term("far", FulltextPhaseOneScorer.MAX_DISTANCE, 1, 1));
			for (int i = 1; i < tokens.length; i++) {
				tokens[i] = token(term("t" + i, 0, 1, 1));
			}
			final FulltextFormula formula = new FulltextFormula(tokens);

			assertArrayEquals(new int[]{1}, formula.compute().getArray());
			assertArrayEquals(new int[]{1}, formula.getFulltextScores(new int[]{1}, 1).primaryKeys());
		}

		@Test
		@DisplayName("A term whose impacts do not align with its postings is refused")
		void shouldRefuseMisalignedImpacts() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new ExpandedTerm(0, "a", new ArrayBitmap(1, 2), ImpactView.of(new byte[]{1}), 0)
			);
		}

		@Test
		@DisplayName("An edit distance outside the scorer's lane is refused")
		void shouldRefuseAnOutOfRangeDistance() {
			assertThrows(GenericEvitaInternalError.class, () -> term("a", -1, 1, 1));
			assertThrows(
				GenericEvitaInternalError.class, () -> term("a", FulltextPhaseOneScorer.MAX_DISTANCE + 1, 1, 1)
			);
		}

		@Test
		@DisplayName("The formula takes no inner formulas")
		void shouldRefuseInnerFormulas() {
			assertThrows(UnsupportedOperationException.class, () -> sampleQuery().getCloneWithInnerFormulas());
		}

		@Test
		@DisplayName("The verbose form names every term")
		void shouldPrintEveryTerm() {
			final String verbose = sampleQuery().toStringVerbose();

			assertTrue(verbose.contains("0:kol (3)"), verbose);
			assertTrue(verbose.contains("0:kolo~1 (1)"), verbose);
			assertTrue(verbose.contains("0:hrad (3)"), verbose);
		}

	}

	@Nested
	@DisplayName("Over a real index")
	class OverARealIndex {

		@Test
		@DisplayName("Random queries match exactly the entities containing every query word in a searched field")
		void shouldMatchLikeABruteForceOracle() {
			final Random random = new Random(42);
			final FulltextIndex index = new FulltextIndex(analyzer);
			final String[] fields = {"title", "body"};
			final int entityCount = 200;
			// per entity and field, the set of analyzed terms - the oracle
			final List<List<Set<String>>> model = new ArrayList<>(entityCount);
			for (int pk = 0; pk < entityCount; pk++) {
				final List<Set<String>> entityTerms = new ArrayList<>(fields.length);
				for (final String field : fields) {
					final String value = randomText(random, 1 + random.nextInt(4));
					index.addValue(attribute(field), pk, value);
					entityTerms.add(termsOf(value));
				}
				model.add(entityTerms);
			}

			// the oracle proves nothing over rounds that all match nothing, so the matching ones are counted
			int matchingRounds = 0;
			int matchingMultiWordRounds = 0;
			for (int round = 0; round < 200; round++) {
				final int wordCount = 1 + random.nextInt(3);
				final List<Set<String>> queryWords = new ArrayList<>(wordCount);
				final ExpandedTerm[][] tokens = new ExpandedTerm[wordCount][];
				for (int w = 0; w < wordCount; w++) {
					final Set<String> wordTerms = termsOf(VOCABULARY[random.nextInt(VOCABULARY.length)]);
					queryWords.add(wordTerms);
					tokens[w] = expand(index, fields, wordTerms);
				}
				final FulltextFormula formula = new FulltextFormula(tokens);

				final TreeSet<Integer> expected = new TreeSet<>();
				for (int pk = 0; pk < entityCount; pk++) {
					if (matchesEveryWord(model.get(pk), queryWords)) {
						expected.add(pk);
					}
				}
				final int[] actual = formula.compute().getArray();
				assertArrayEquals(
					expected.stream().mapToInt(Integer::intValue).toArray(), actual,
					() -> "Round " + formula.toStringVerbose()
				);
				if (actual.length > 0) {
					matchingRounds++;
					if (wordCount >= 2) {
						matchingMultiWordRounds++;
					}
					assertSameScores(
						FulltextPhaseOneScorer.score(actual, expansionsOf(formula), actual.length),
						formula.getFulltextScores(actual, actual.length)
					);
				}
			}
			assertTrue(matchingRounds > 0, "Some round must match an entity.");
			assertTrue(matchingMultiWordRounds > 0, "Some round of several words must match an entity.");
		}

		@Test
		@DisplayName("Postings past the sorted-array tier match and score like array-tier ones, and stay untouched")
		void shouldMatchAndScoreOverBitmapTierPostings() {
			final FulltextIndex index = new FulltextIndex(analyzer);
			final int body = index.getOrAssignFieldId(attribute("body"));
			final int title = index.getOrAssignFieldId(attribute("title"));
			// two terms past the sorted-array tier, into bitmaps spanning three roaring containers, sharing every
			// multiple of 97 * 89; and a term in the array tier on multiples of 89 alone
			final TreeSet<Integer> kolo = new TreeSet<>();
			final TreeSet<Integer> hrad = new TreeSet<>();
			final TreeSet<Integer> kola = new TreeSet<>();
			for (int primaryKey = 0; primaryKey <= 140_000; primaryKey += 97) {
				index.addPosting(body, "kolo", primaryKey, 1 + primaryKey % 255);
				kolo.add(primaryKey);
			}
			for (int primaryKey = 0; primaryKey <= 140_000; primaryKey += 89) {
				index.addPosting(body, "hrad", primaryKey, 1 + primaryKey % 251);
				hrad.add(primaryKey);
			}
			for (int multiple = 1; multiple <= 10; multiple++) {
				index.addPosting(title, "kola", 89 * multiple, multiple);
				kola.add(89 * multiple);
			}
			assertInstanceOf(TransactionalBitmap.class, index.getPostings(body, "kolo"), "The fixture needs a bitmap.");
			assertInstanceOf(TransactionalBitmap.class, index.getPostings(body, "hrad"), "The fixture needs a bitmap.");
			final int[] koloBefore = index.getPostings(body, "kolo").getArray();
			final int[] hradBefore = index.getPostings(body, "hrad").getArray();

			final String[] fields = {"body", "title"};
			// a two-term token takes the union branch, a single-term one the branch adopting the posting bitmap
			final FulltextFormula formula = new FulltextFormula(
				new ExpandedTerm[][]{
					expand(index, fields, Set.of("kolo", "kola")),
					expand(index, fields, Set.of("hrad"))
				}
			);
			final TreeSet<Integer> expected = new TreeSet<>(kolo);
			expected.addAll(kola);
			expected.retainAll(hrad);
			final int[] actual = formula.compute().getArray();

			assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(), actual);
			assertTrue(actual.length > kola.size(), "Both tiers must contribute to the match.");
			assertSameScores(
				FulltextPhaseOneScorer.score(actual, expansionsOf(formula), actual.length),
				formula.getFulltextScores(actual, actual.length)
			);
			// the formula reads the dictionary's own posting bitmaps, it must never write them
			assertArrayEquals(koloBefore, index.getPostings(body, "kolo").getArray());
			assertArrayEquals(hradBefore, index.getPostings(body, "hrad").getArray());
		}

		/**
		 * Composes a text of random vocabulary words.
		 *
		 * @param random    the source of randomness
		 * @param wordCount how many words
		 * @return the text
		 */
		@Nonnull
		private String randomText(@Nonnull Random random, int wordCount) {
			final StringBuilder sb = new StringBuilder(wordCount * 12);
			for (int i = 0; i < wordCount; i++) {
				sb.append(VOCABULARY[random.nextInt(VOCABULARY.length)]).append(' ');
			}
			return sb.toString();
		}

		/**
		 * Analyzes a text into the set of its terms.
		 *
		 * @param text the text
		 * @return the terms
		 */
		@Nonnull
		private Set<String> termsOf(@Nonnull String text) {
			final Set<String> terms = new TreeSet<>();
			for (final AnalyzedTerm term : analyzer.getTerms(text)) {
				terms.add(term.term());
			}
			return terms;
		}

		/**
		 * Expands a query word's terms into the terms of every searched field the index holds them in.
		 *
		 * @param index     the index
		 * @param fields    the searched fields
		 * @param wordTerms the analyzed terms of the word
		 * @return the token
		 */
		@Nonnull
		private ExpandedTerm[] expand(
			@Nonnull FulltextIndex index,
			@Nonnull String[] fields,
			@Nonnull Set<String> wordTerms
		) {
			final List<ExpandedTerm> result = new ArrayList<>(fields.length * wordTerms.size());
			for (final String field : fields) {
				final int fieldId = index.getFieldId(attribute(field));
				for (final String term : wordTerms) {
					final Bitmap postings = index.getPostings(fieldId, term);
					if (!postings.isEmpty()) {
						result.add(
							new ExpandedTerm(fieldId, term, postings, ImpactView.of(index.getImpacts(fieldId, term)), 0)
						);
					}
				}
			}
			return result.toArray(ExpandedTerm[]::new);
		}

		/**
		 * The oracle: does the entity contain, for every query word, one of the word's terms in some field?
		 *
		 * @param entityTerms the entity's terms per field
		 * @param queryWords  the terms of each query word
		 * @return true when every word is matched
		 */
		private boolean matchesEveryWord(
			@Nonnull List<Set<String>> entityTerms,
			@Nonnull List<Set<String>> queryWords
		) {
			for (final Set<String> word : queryWords) {
				boolean found = false;
				for (final Set<String> fieldTerms : entityTerms) {
					for (final String term : word) {
						if (fieldTerms.contains(term)) {
							found = true;
							break;
						}
					}
				}
				if (!found) {
					return false;
				}
			}
			return true;
		}

	}

	@Nested
	@DisplayName("Behind the prefetch wrappers")
	class BehindPrefetchWrappers {

		@Test
		@DisplayName("A SHALLOW lookup stops at the selection wrapper, which therefore has to delegate the scores")
		void shouldStopAtTheSelectionWrapper() {
			final FulltextFormula fulltext = sampleQuery();
			final SelectionFormula selection = new SelectionFormula(fulltext, new FailingFilter());
			final Formula root = new AndFormula(selection, new ConstantFormula(new ArrayBitmap(1, 2, 3)));

			final Collection<FulltextScoreAccessor> found =
				FormulaFinder.find(root, FulltextScoreAccessor.class, LookUp.SHALLOW);

			// this is the trap: the fulltext formula itself is never reached, only the wrapper above it
			assertEquals(List.of(selection), List.copyOf(found));
			assertTrue(selection.providesFulltextScores());
			final int[] candidates = {1, 2, 3, 7};
			assertSameScores(fulltext.getFulltextScores(candidates, 3), selection.getFulltextScores(candidates, 3));
		}

		@Test
		@DisplayName("The selection wrapper finds the fulltext formula in a container and in a nested wrapper")
		void shouldDelegateThroughContainersAndNestedWrappers() {
			final FulltextFormula fulltext = sampleQuery();
			final SelectionFormula inner = new SelectionFormula(fulltext, new FailingFilter());
			final SelectionFormula outer = new SelectionFormula(
				new AndFormula(inner, new ConstantFormula(new ArrayBitmap(2, 3))), new FailingFilter()
			);
			final int[] candidates = {2, 3};

			assertTrue(outer.providesFulltextScores());
			assertSameScores(fulltext.getFulltextScores(candidates, 2), outer.getFulltextScores(candidates, 2));
		}

		@Test
		@DisplayName("Scores never come from the alternative - the score is keyed by primary key, not entity content")
		void shouldNeverAskTheAlternativeForScores() {
			final FulltextFormula fulltext = sampleQuery();
			final ScoringFilter alternative = new ScoringFilter(null);
			final SelectionFormula selection = new SelectionFormula(fulltext, alternative);
			final int[] candidates = {1, 2, 3};

			assertSameScores(fulltext.getFulltextScores(candidates, 3), selection.getFulltextScores(candidates, 3));
			assertFalse(alternative.asked);
		}

		@Test
		@DisplayName("A selection wrapper without a fulltext formula inside provides no scores")
		void shouldProvideNoScoresWithoutAFulltextFormula() {
			final SelectionFormula selection = new SelectionFormula(
				new ConstantFormula(new ArrayBitmap(1, 2)), new FailingFilter()
			);

			assertFalse(selection.providesFulltextScores());
			assertThrows(GenericEvitaInternalError.class, () -> selection.getFulltextScores(new int[]{1}, 1));
		}

		@Test
		@DisplayName("A selection wrapper without fulltext does not count as a score source of an outer wrapper")
		void shouldSkipWrappersWithoutScores() {
			final FulltextFormula fulltext = sampleQuery();
			final SelectionFormula plain = new SelectionFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)), new FailingFilter()
			);
			final SelectionFormula outer = new SelectionFormula(new AndFormula(plain, fulltext), new FailingFilter());
			final int[] candidates = {2, 3};

			assertSameScores(fulltext.getFulltextScores(candidates, 2), outer.getFulltextScores(candidates, 2));
		}

		@Test
		@DisplayName("A selection wrapper over two fulltext formulas refuses to pick one")
		void shouldRefuseTwoScoreSources() {
			final SelectionFormula selection = new SelectionFormula(
				new AndFormula(sampleQuery(), query(token(term("x", 0, 2, 1)))), new FailingFilter()
			);

			assertTrue(selection.providesFulltextScores());
			assertThrows(GenericEvitaInternalError.class, () -> selection.getFulltextScores(new int[]{2}, 1));
		}

		@Test
		@DisplayName("The entity filtering wrapper delegates to an alternative that provides scores")
		void shouldDelegateEntityFilteringToAScoringAlternative() {
			final FulltextFormula fulltext = sampleQuery();
			final EntityFilteringFormula filtering = new EntityFilteringFormula("test", new ScoringFilter(fulltext));
			final int[] candidates = {1, 2, 3};

			assertTrue(filtering.providesFulltextScores());
			assertSameScores(fulltext.getFulltextScores(candidates, 3), filtering.getFulltextScores(candidates, 3));
		}

		@Test
		@DisplayName("The entity filtering wrapper over a plain alternative provides no scores")
		void shouldProvideNoScoresFromAPlainEntityFilteringAlternative() {
			final EntityFilteringFormula plain = new EntityFilteringFormula("test", new FailingFilter());
			final EntityFilteringFormula silent = new EntityFilteringFormula("test", new ScoringFilter(null));

			assertFalse(plain.providesFulltextScores());
			assertFalse(silent.providesFulltextScores());
			assertThrows(GenericEvitaInternalError.class, () -> plain.getFulltextScores(new int[]{1}, 1));
		}

	}

	/**
	 * An alternative filter that must never be used - every test here exercises the delegate path.
	 */
	private static final class FailingFilter implements EntityToBitmapFilter {

		@Nullable
		@Override
		public EntityFetchRequire getEntityRequire() {
			return null;
		}

		@Nonnull
		@Override
		public Bitmap filter(@Nonnull QueryExecutionContext context) {
			throw new AssertionError("The alternative must not be evaluated.");
		}

	}

	/**
	 * An alternative filter that is also a fulltext score source, either backed by a formula or providing none, and
	 * records whether it was asked for scores.
	 */
	private static final class ScoringFilter implements EntityToBitmapFilter, FulltextScoreAccessor {
		/**
		 * The formula the scores come from, or null when this source provides none.
		 */
		@Nullable private final FulltextFormula source;
		/**
		 * Whether scores were requested.
		 */
		private boolean asked;

		ScoringFilter(@Nullable FulltextFormula source) {
			this.source = source;
		}

		@Nullable
		@Override
		public EntityFetchRequire getEntityRequire() {
			return null;
		}

		@Nonnull
		@Override
		public Bitmap filter(@Nonnull QueryExecutionContext context) {
			throw new AssertionError("The alternative must not be evaluated.");
		}

		@Override
		public boolean providesFulltextScores() {
			return this.source != null;
		}

		@Nonnull
		@Override
		public Result getFulltextScores(@Nonnull int[] candidates, int topN) {
			this.asked = true;
			if (this.source == null) {
				throw new AssertionError("This source provides no scores.");
			}
			return this.source.getFulltextScores(candidates, topN);
		}

	}

	/**
	 * Draws about `count` distinct primary keys below `bound`, ascending.
	 *
	 * @param random the source of randomness
	 * @param count  how many keys to draw on average
	 * @param bound  exclusive upper bound of the keys
	 * @return the keys, at least one
	 */
	@Nonnull
	private static int[] randomPostings(@Nonnull Random random, int count, int bound) {
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
